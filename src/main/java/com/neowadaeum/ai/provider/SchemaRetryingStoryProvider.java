package com.neowadaeum.ai.provider;

import com.neowadaeum.ai.schema.TurnOutputSchemaException;

/**
 * 출력 스키마를 못 맞춘 응답을 다시 요청한다 (R5.8, R3.3, §6.1-5, B-21).
 *
 * <p><b>재요청 횟수가 Provider 의 능력으로 갈린다.</b>
 *
 * <table border="1">
 * <caption>재요청 횟수</caption>
 * <tr><th>{@code structuredOutput}</th><th>재요청</th><th>근거</th></tr>
 * <tr><td>{@code true}</td><td>1회</td><td>R5.8</td></tr>
 * <tr><td>{@code false}</td><td>2회</td><td>R3.3 — 스키마를 강제할 수단이 프롬프트밖에 없다</td></tr>
 * </table>
 *
 * <p><b>데코레이터로 둔 이유는 {@link TimeLimitedStoryProvider} 와 같다.</b> 재요청은 어느
 * 어댑터를 붙이든 같은 규칙이고, 각 어댑터가 스스로 세게 하면 새 어댑터가 그것을 잊는다. 잊은
 * 사실은 사용자가 502 를 받은 뒤에 알게 된다.
 *
 * <p><b>같은 요청을 그대로 다시 보낸다.</b> "이번엔 JSON 으로 답하라" 같은 교정 문구를 덧붙이지
 * 않는 것은, 그 문구가 {@code SYSTEM} 과 {@code OUTPUT SPEC} 밖에서 프롬프트를 바꾸는 아홉 번째
 * 레이어가 되기 때문이다 (I-7). 형식을 못 맞춘 원인이 프롬프트에 있다면 고칠 곳은 B-20 의
 * {@code OUTPUT_SPEC} 이지 재요청 경로가 아니다.
 *
 * <p><b>{@link #generateTurn} 에만 건다.</b> {@link #summarize} 는 문자열을 돌려주므로 맞출
 * 스키마가 없고, {@link #draftOutline} 의 출력 계약은 B-52 가 정한다. <b>지금 없는 계약을 위해
 * 빈 재요청을 걸어 두지 않는다</b> — 걸어 두면 그 경로의 실패가 조용히 두 배로 청구된다.
 *
 * <p><b>시간 제한 안쪽에 놓인다</b> ({@code AiGatewayConfiguration}). §6.1 은 4단계(호출, 25s)와
 * 5단계(재요청)를 나란히 두고 §6.3 은 서버 전체 예산을 28s 로 못박는데, 재요청이 제한 밖이면
 * 두 번의 호출이 50s 가 되어 예산을 넘는다. 원문이 정하지 않은 지점이라
 * {@code docs/corrections.md} 에 올렸다.
 */
public class SchemaRetryingStoryProvider implements StoryProvider {

	private final StoryProvider delegate;

	public SchemaRetryingStoryProvider(StoryProvider delegate) {
		this.delegate = delegate;
	}

	@Override
	public String providerId() {
		return this.delegate.providerId();
	}

	@Override
	public ProviderCapabilities capabilities() {
		return this.delegate.capabilities();
	}

	/**
	 * 스키마 위반이면 다시 요청하고, 허용된 횟수를 다 쓰면 {@link OutputSchemaRejectedException} 을
	 * 던진다. 호출자는 이것을 {@code 502 PROVIDER_ERROR} 로 바꾼다 (R5.8).
	 *
	 * <p><b>스키마 위반만 다시 요청한다.</b> 시간 초과·연결 실패는 그대로 올려 보낸다 — 같은 요청을
	 * 다시 보내 나아지는 종류의 실패가 아니고, 시간 초과는 이미 25s 를 쓴 뒤다.
	 */
	@Override
	public TurnResult generateTurn(TurnRequest request) {
		int allowedRetries = allowedRetries();

		TurnOutputSchemaException lastViolation = null;
		for (int attempt = 0; attempt <= allowedRetries; attempt++) {
			try {
				return this.delegate.generateTurn(request);
			}
			catch (TurnOutputSchemaException ex) {
				lastViolation = ex;
			}
		}
		throw new OutputSchemaRejectedException(allowedRetries + 1, lastViolation);
	}

	/** R5.8 / R3.3 — 구조화 출력을 강제할 수 없는 Provider 에게 한 번 더 준다. */
	private int allowedRetries() {
		return this.delegate.capabilities().structuredOutput() ? 1 : 2;
	}

	@Override
	public String summarize(SummaryRequest request) {
		return this.delegate.summarize(request);
	}

	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		return this.delegate.draftOutline(request);
	}

	/**
	 * 허용된 횟수를 다 쓰고도 스키마를 만족하지 못했다 (R5.8).
	 *
	 * <p><b>{@code ai :: provider} 에 있는 것이 요점이다.</b> {@code play} 가 허용받은 의존은 이
	 * 패키지 하나이므로 (ADR-0005), 내부 예외인 {@code TurnOutputSchemaException} 이 그대로 밖으로
	 * 나가면 호출자가 §5.4 를 넘어야 그것을 잡을 수 있다.
	 *
	 * <p><b>메시지에 응답 원문이 없다</b> (S-3). 원인 예외는 어긋난 지점까지만 담고 있다.
	 */
	public static class OutputSchemaRejectedException extends RuntimeException {

		public OutputSchemaRejectedException(int attempts, Throwable lastViolation) {
			super("provider output did not match the turn schema in " + attempts + " attempts", lastViolation);
		}
	}
}
