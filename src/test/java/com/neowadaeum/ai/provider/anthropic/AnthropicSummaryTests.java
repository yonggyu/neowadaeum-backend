package com.neowadaeum.ai.provider.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.neowadaeum.ai.log.AiCallLog;
import com.neowadaeum.ai.prompt.PromptAssembler;
import com.neowadaeum.ai.prompt.TurnPromptFactory;
import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.ai.schema.TurnOutputParser;
import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.play.port.ProviderCallFailedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic 어댑터의 요약 계약 (B-34, R4.5 · R3.6).
 *
 * <p>실제 AI 를 부르지 않는다. 보는 것은 <b>어느 모델로 무엇을 보내고, 무엇을 저장하지 않는가</b>다.
 */
class AnthropicSummaryTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String TURN_MODEL = "claude-opus-5";

	private static final String SUMMARY_MODEL = "claude-haiku-4-5";

	private final List<AiCallLog.Draft> recorded = Collections.synchronizedList(new ArrayList<>());

	private WireMockServer server;

	private AnthropicStoryProvider provider;

	@BeforeEach
	void startServer() {
		this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
		this.server.start();
		this.provider = adapter(new AnthropicProperties.Models(TURN_MODEL, SUMMARY_MODEL, null, null));
	}

	@AfterEach
	void stopServer() {
		this.server.stop();
	}

	private AnthropicStoryProvider adapter(AnthropicProperties.Models models) {
		AnthropicProperties properties = new AnthropicProperties("test-key", models,
				"http://localhost:" + this.server.port(), 4096, null);
		return new AnthropicStoryProvider(
				RestClient.builder().baseUrl(properties.baseUrl()).defaultHeader("x-api-key", "test-key").build(),
				properties,
				new TurnPromptFactory(new PromptAssembler(new FixedTokenCounter(), RecentTurnsProperties.defaults())),
				new TurnOutputParser(),
				this.recorded::add);
	}

	private void respondWith(String text) {
		this.server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
				.withStatus(200)
				.withHeader("content-type", "application/json")
				.withBody("{\"content\":[{\"type\":\"text\",\"text\":%s}]}".formatted(JSON.writeValueAsString(text)))));
	}

	private static SummaryRequest request() {
		return new SummaryRequest("지금까지의 줄거리",
				List.of(new SummaryRequest.TurnDigest(9, "왼쪽 길", "아홉째 턴의 요지"),
						new SummaryRequest.TurnDigest(10, "말을 건다", "열째 턴의 요지")),
				600);
	}

	/**
	 * <b>R3.6 — 요약은 요약용 모델이 한다.</b>
	 *
	 * <p>턴 생성 모델로 요약하면 <b>요약 한 번이 턴 생성만큼</b> 든다. 사용자가 기다리지 않는
	 * 호출이라 눈에 띄지도 않는다 — 청구서로만 드러난다.
	 */
	@Test
	void R3_6_the_summary_call_uses_the_summary_model() {
		respondWith("압축된 줄거리");

		assertThat(this.provider.summarize(request())).isEqualTo("압축된 줄거리");

		String sent = this.server.getAllServeEvents().getFirst().getRequest().getBodyAsString();
		assertThat(JSON.readTree(sent).path("model").asString(""))
				.isEqualTo(SUMMARY_MODEL)
				.isNotEqualTo(TURN_MODEL);
	}

	/** 요약용 모델이 없으면 턴 생성 모델을 빌려 쓰지 않는다 — 호출 자체가 나가지 않는다 (R3.6). */
	@Test
	void R3_6_a_missing_summary_model_fails_before_the_call() {
		AnthropicStoryProvider withoutSummaryModel = adapter(
				new AnthropicProperties.Models(TURN_MODEL, null, null, null));

		assertThatThrownBy(() -> withoutSummaryModel.summarize(request()))
				.isInstanceOf(ProviderCallFailedException.class);

		assertThat(this.server.getAllServeEvents()).isEmpty();
	}

	/**
	 * <b>R4.5 — 직전 요약과 병합 대상 턴이 함께 간다.</b>
	 *
	 * <p>압축은 이어 쓰는 일이다. 새 턴만 주면 모델은 그 앞의 이야기를 모른 채 요약하고, 그렇게
	 * 만들어진 요약이 다음 턴의 전제가 된다.
	 */
	@Test
	void R4_5_the_previous_summary_and_the_merged_turns_are_both_sent() {
		respondWith("압축된 줄거리");

		this.provider.summarize(request());

		String content = JSON.readTree(this.server.getAllServeEvents().getFirst().getRequest().getBodyAsString())
				.path("messages").get(0).path("content").asString("");
		assertThat(content)
				.contains("지금까지의 줄거리")
				.contains("아홉째 턴의 요지")
				.contains("왼쪽 길")
				.contains("열째 턴의 요지");
	}

	/** {@code max_tokens} 는 요청이 정한 예산이다 (§4.3 의 SUMMARY 600). */
	@Test
	void S4_3_the_budget_is_sent_as_the_output_limit() {
		respondWith("압축된 줄거리");

		this.provider.summarize(request());

		assertThat(JSON.readTree(this.server.getAllServeEvents().getFirst().getRequest().getBodyAsString())
				.path("max_tokens").asInt()).isEqualTo(600);
	}

	/** B-25 — 요약 호출도 기록된다. 용도가 갈려야 통계에서 턴 생성과 섞이지 않는다. */
	@Test
	void B25_the_summary_call_is_recorded_with_its_own_purpose() {
		respondWith("압축된 줄거리");

		this.provider.summarize(request());

		assertThat(this.recorded).hasSize(1);
		assertThat(this.recorded.getFirst().purpose()).isEqualTo("summary");
		assertThat(this.recorded.getFirst().modelId()).isEqualTo(SUMMARY_MODEL);
	}

	/**
	 * <b>빈 요약을 돌려주지 않는다.</b>
	 *
	 * <p>빈 문자열을 저장하면 그 세션은 그 뒤로 아무것도 기억하지 못하고, 증상은 <b>"이야기가
	 * 갑자기 앞을 잊는다"</b>로 나타나 원인을 찾기 어렵다.
	 */
	@Test
	void R4_5_a_blank_summary_is_a_call_failure() {
		respondWith("   ");

		assertThatThrownBy(() -> this.provider.summarize(request()))
				.isInstanceOf(ProviderCallFailedException.class);
	}

	/** 벤더 장애도 호출 실패다 — 요약은 다음 턴에 다시 시도된다 (R4.6, 턴을 실패시키지 않는다). */
	@Test
	void R4_6_a_dead_vendor_is_a_call_failure() {
		this.server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(503)));

		assertThatThrownBy(() -> this.provider.summarize(request()))
				.isInstanceOf(ProviderCallFailedException.class);
		assertThat(this.recorded).as("실패한 호출도 요청은 남는다 (B-25)").hasSize(1);
	}
}
