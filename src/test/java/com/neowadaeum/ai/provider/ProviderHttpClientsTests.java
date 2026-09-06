package com.neowadaeum.ai.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 어댑터가 벤더에게 말할 때 쓰는 HTTP 클라이언트 (#93 · #374).
 *
 * <p>여기 있는 규칙은 <b>벤더가 아니라 플랫폼의 것</b>이다 — 그래서 이 테스트는 어느 어댑터의
 * 등록 테스트에도 얹히지 않는다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class ProviderHttpClientsTests {

	private WireMockServer server;

	@BeforeEach
	void startServer() {
		this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.server.stop();
	}

	/**
	 * <b>소켓 읽기 상한은 생성 예산보다 항상 크다</b> (#93 점검, §13-19).
	 *
	 * <p>작거나 같으면 <b>상한이 먼저 끊고</b>, 시간 초과가 {@code 504} 가 아니라 {@code 502} 로
	 * 나간다 — 원인은 시간인데 표시는 벤더 장애가 된다. 상수로 고정해 두면 예산을 그보다 크게
	 * 잡는 순간 이 오분류가 조용히 생긴다.
	 */
	@Test
	void S13_19_the_socket_ceiling_never_preempts_the_generation_budget() {
		assertThat(ProviderHttpClients.socketReadCeiling(new ProviderProperties(Duration.ofSeconds(25), null)))
				.isGreaterThan(Duration.ofSeconds(25));

		assertThat(ProviderHttpClients.socketReadCeiling(new ProviderProperties(Duration.ofSeconds(90), null)))
				.as("예산을 90초로 올려도 상한이 먼저 끊으면 안 된다")
				.isGreaterThan(Duration.ofSeconds(90));
	}

	/**
	 * <b>와이어 클라이언트는 클래스패스가 아니라 우리가 고른다</b> (#374).
	 *
	 * <p>{@code RestClient.builder()} 를 요청 팩토리 없이 쓰면 스프링이 <b>그때 클래스패스에 있는
	 * 것</b>을 집는다. 그러면 AI 와 아무 상관 없는 의존 하나가 모델과 말하는 방식을 바꾼다 —
	 * 실제로 #315 의 객체 저장소 SDK 가 그렇게 했다. 취소는 인터럽트로 닿아야 하므로(R6.4)
	 * <b>고르는 쪽이 코드여야 한다.</b>
	 */
	@Test
	void R6_4_the_wire_client_is_pinned_instead_of_detected() {
		assertThat(ProviderHttpClients.requestFactory(new ProviderProperties(null, null)))
				.isInstanceOf(JdkClientHttpRequestFactory.class);
	}

	/**
	 * <b>상한이 실제로 걸린다.</b> 값 계산만 맞고 팩토리에 붙지 않으면, 멈춘 벤더를 기다리는 호출은
	 * 영영 돌아오지 않는다 — 그 실패는 예산을 넘긴 뒤에야, 그것도 스레드 하나를 잃은 채로 드러난다.
	 */
	@Test
	void R6_4_a_vendor_that_never_answers_is_cut_by_the_socket_ceiling() {
		this.server.stubFor(post(urlEqualTo("/hang"))
				.willReturn(aResponse().withStatus(200).withFixedDelay((int) Duration.ofSeconds(30).toMillis())));
		RestClient client = RestClient.builder()
				.baseUrl("http://localhost:" + this.server.port())
				.requestFactory(ProviderHttpClients.requestFactory(
						new ProviderProperties(Duration.ofMillis(300), null)))
				.build();

		long startedAt = System.nanoTime();
		assertThatThrownBy(() -> client.post().uri("/hang").retrieve().toBodilessEntity())
				.isInstanceOf(RestClientException.class);

		assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
				.as("상한이 붙지 않았다면 30초를 기다렸을 것이다")
				.isLessThan(Duration.ofSeconds(10));
	}
}
