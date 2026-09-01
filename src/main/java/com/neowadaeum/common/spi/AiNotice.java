package com.neowadaeum.common.spi;

/**
 * 지금 보여 줘야 할 AI 사전 고지 (R11.1, §11).
 *
 * <p><b>문구가 코드에 없다.</b> 값은 {@code service_config} 에서 오며, 법이 요구하는 문구는
 * 고시와 함께 바뀌므로 그때마다 배포가 필요하면 늦는다.
 *
 * @param version 이 문구의 판본. <b>노출 이력이 가리키는 값</b>이다 (R11.3) — 문구가 바뀌면
 *                판본이 바뀌고, 그때 다시 보여 줘야 한다
 * @param text    화면에 그대로 나가는 문구
 */
public record AiNotice(String version, String text) {

	public AiNotice {
		if (version == null || version.isBlank() || text == null || text.isBlank()) {
			// 판본 없는 문구는 "무엇을 보여 줬는가"를 남길 수 없다.
			throw new IllegalArgumentException("version, text are required");
		}
	}
}
