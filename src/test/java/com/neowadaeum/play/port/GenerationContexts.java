package com.neowadaeum.play.port;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 생성 컨텍스트 (B-22).
 *
 * <p><b>재료가 무엇인지에 관심 없는 테스트를 위한 것이다.</b> 시간 제한 · 재요청 · 배선을 보는
 * 테스트는 프롬프트 재료가 무엇이든 결과가 같아야 한다 — 매번 손으로 채우면 그 자리에 서로 다른
 * 값이 들어가고, 나중에 <b>어느 값이 결과에 영향을 주는지</b> 알 수 없게 된다.
 *
 * <p>재료 자체를 보는 테스트(프롬프트 골든 파일)는 이것을 쓰지 않고 직접 만든다.
 */
public final class GenerationContexts {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private GenerationContexts() {
	}

	/** 최소한으로 유효한 컨텍스트. 필수는 {@code worldPrompt} 와 {@code gameState} 뿐이다. */
	public static GenerationContext sample() {
		return new GenerationContext("눈이 오래 내리는 도시.", List.of(), JSON.readTree("{}"), null, List.of(), null);
	}

	/**
	 * <b>모든 자리가 채워진</b> 컨텍스트.
	 *
	 * <p>필요한 이유가 있다 — {@link #sample()} 은 목록이 비어 있어 <b>중첩 안쪽 이름이 직렬화
	 * 결과에 나타나지 않는다.</b> I-3 화이트리스트의 드리프트 검사처럼 "실제로 나가는 이름"을
	 * 세는 테스트가 그것을 쓰면 {@code persona} · {@code paragraphsDigest} 같은 이름이 <b>검사
	 * 대상에서 조용히 빠진다.</b>
	 */
	public static GenerationContext populated() {
		return new GenerationContext(
				"눈이 오래 내리는 도시.",
				List.of(new GenerationContext.Character("유나", "말수가 적고 문장이 짧다.")),
				JSON.readTree("{\"chapter\":1,\"turn\":1}"),
				"주인공은 유나와 두 번 마주쳤다.",
				List.of(new GenerationContext.RecentTurn(1, "먼저 인사한다", "복도에서 마주쳤다.", "복도 조우")),
				"고맙다고 말한다");
	}
}
