package com.neowadaeum.ai.provider;

/**
 * Provider 가 스스로 밝히는 능력 (§3, B-18).
 *
 * <p><b>호출자가 분기하기 위한 값이지 설명문이 아니다.</b> 세 값 모두 실제로 코드가 읽는다.
 *
 * <ul>
 *   <li>{@code structuredOutput} — {@code false} 면 JSON 파싱 실패 시 재요청 횟수가 달라진다 (R3.3).
 *       B-21 의 파서가 이 값으로 갈린다
 *   <li>{@code maxContextTokens} — 프롬프트 조립기가 예산을 맞출 상한 (B-20, §4.3)
 *   <li>{@code supportsSystemRole} — {@code false} 면 {@code SYSTEM} 레이어를 첫 사용자 메시지에
 *       접어 넣어야 한다. <b>레이어를 생략하는 선택지는 없다</b> — I-7 은 provider 사정과 무관하다
 * </ul>
 *
 * @param structuredOutput   스키마를 강제한 구조화 출력을 지원하는가
 * @param maxContextTokens   입력으로 받을 수 있는 토큰 상한. 모델을 쓰지 않는 Provider 는 {@code 0}
 * @param supportsSystemRole 별도의 system 역할을 지원하는가
 */
public record ProviderCapabilities(boolean structuredOutput, int maxContextTokens, boolean supportsSystemRole) {

	public ProviderCapabilities {
		if (maxContextTokens < 0) {
			throw new IllegalArgumentException("maxContextTokens must not be negative");
		}
	}

	/**
	 * 모델을 호출하지 않는 Provider 의 능력.
	 *
	 * <p>결정론 Provider(S-3)처럼 시나리오 파일을 읽는 구현이 여기 해당한다. 출력은 이미 구조화되어
	 * 있고, 프롬프트를 소비하지 않으므로 컨텍스트 상한이 <b>없는 것이 아니라 의미가 없다</b> —
	 * {@code 0} 이 그 뜻이며, 예산 계산이 이 Provider 를 기준으로 이뤄지면 즉시 드러난다.
	 */
	public static ProviderCapabilities withoutModel() {
		return new ProviderCapabilities(true, 0, false);
	}
}
