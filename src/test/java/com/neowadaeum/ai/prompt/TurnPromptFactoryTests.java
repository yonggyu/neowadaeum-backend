package com.neowadaeum.ai.prompt;

import com.neowadaeum.common.support.RecentTurnsProperties;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.common.support.FixedTokenCounter;
import com.neowadaeum.play.port.GenerationContext;
import com.neowadaeum.play.port.TurnRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 포트 계약이 실제 프롬프트가 되는지 (B-22, §5.1).
 *
 * <p><b>같은 골든 파일을 쓴다.</b> {@code PromptAssemblerTests} 는 {@link PromptContext} 에서
 * 출발하고 이 테스트는 <b>{@code play} 가 실제로 보내는 계약</b>에서 출발한다. 두 입구가 같은
 * 파일로 수렴하지 않으면 매핑이 뭔가를 잃은 것이다 — 개수를 세는 단언보다 이쪽이 강하다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class TurnPromptFactoryTests {

	private static final Path GOLDEN = Path.of("src/test/resources/prompt/golden/turn-prompt.txt");

	private static final UUID STORY_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	/** 골든 안정성을 위해 고정 계산기를 쓴다 (#82). {@code PromptAssemblerTests} 와 같은 이유다. */
	private final TurnPromptFactory factory = new TurnPromptFactory(
			new PromptAssembler(new FixedTokenCounter(), RecentTurnsProperties.defaults()));

	/**
	 * <b>{@code play} 가 보낸 재료가 한 자도 잃지 않고 프롬프트가 된다.</b>
	 *
	 * <p>여기 담긴 값은 {@code PromptAssemblerTests.context()} 와 같은 것이며, 다른 것은
	 * <b>어느 타입으로 출발하는가</b>뿐이다.
	 */
	@Test
	void B22_the_port_contract_renders_to_the_same_prompt() throws IOException {
		String rendered = this.factory.create(request()).render();

		assertThat(Files.readString(GOLDEN, StandardCharsets.UTF_8))
				.withFailMessage("포트 계약에서 출발한 프롬프트가 골든과 다르다 — 매핑이 무언가를 잃었다")
				.isEqualTo(rendered);
	}

	/**
	 * <b>압축본이 없으면 원문을 쓴다.</b> 요약 파이프라인(B-34)이 붙기 전까지
	 * {@code paragraphsDigest} 는 언제나 {@code null} 이며, 그때 맥락이 사라지지 않아야 한다.
	 *
	 * <p>빈 문자열을 넣어 "압축본이 있다"고 꾸미면 조립기가 그것을 싣고 <b>본문이 조용히
	 * 사라진다.</b> 예산 초과는 드러나지만 사라진 맥락은 드러나지 않는다.
	 */
	@Test
	void B34_a_missing_digest_falls_back_to_the_verbatim_body() {
		GenerationContext context = new GenerationContext(
				"눈이 오래 내리는 도시.",
				List.of(),
				JsonMapper.builder().build().readTree("{}"),
				GenerationContext.StateVocabulary.none(),
				null,
				List.of(new GenerationContext.RecentTurn(1, "먼저 인사한다", "복도에서 마주쳤다.", null),
						new GenerationContext.RecentTurn(2, null, "우산을 내밀었다.", null),
						new GenerationContext.RecentTurn(3, null, "말없이 걸었다.", null)),
				"고맙다고 말한다");

		String rendered = this.factory.create(
				TurnRequest.of(STORY_VERSION, 3, 1, context)).render();

		assertThat(rendered).contains("복도에서 마주쳤다.", "우산을 내밀었다.", "말없이 걸었다.");
	}

	private static TurnRequest request() {
		GenerationContext context = new GenerationContext(
				"눈이 오래 내리는 도시. 사람들은 서로의 이름을 잘 부르지 않는다.",
				List.of(new GenerationContext.Character("유나", "무뚝뚝하지만 먼저 챙긴다. 말끝을 흐린다.")),
				JsonMapper.builder().build().readTree(
						"{\"chapter\":2,\"turn\":7,\"location\":\"강의실\",\"affinity\":{\"yuna\":18}}"),
				new GenerationContext.StateVocabulary(List.of("affinity.yuna", "affinity.dohyun"),
						List.of("met_yuna", "shared_lunch"), List.of()),
				"주인공은 유나와 두 번 마주쳤고, 두 번 다 말을 걸지 못했다.",
				List.of(new GenerationContext.RecentTurn(6, "먼저 인사한다",
								"복도에서 유나가 먼저 고개를 돌렸다. 눈이 어깨에 조금 쌓여 있었다.",
								"복도에서 유나가 먼저 고개를 돌렸다."),
						new GenerationContext.RecentTurn(7, null,
								"유나가 우산을 내밀었다. 받으라는 말은 하지 않았다.",
								"유나가 우산을 내밀었다.")),
				"고맙다고 말한다");

		return TurnRequest.of(STORY_VERSION, 7, 1, context);
	}
}
