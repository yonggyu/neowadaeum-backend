package com.neowadaeum.ai.provider;

import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.ai.schema.OutlineOutputSchemaException;
import com.neowadaeum.ai.schema.TurnOutputSchemaException;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.OutputSchemaRejectedException;
import com.neowadaeum.play.port.TurnRequest;
import java.util.Set;

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
 * <p><b>{@link #generateTurn} 과 {@link #draftOutline} 에 건다.</b> {@link #summarize} 는 문자열을
 * 돌려주므로 맞출 스키마가 없고, {@link #classifySafety} 의 형식 위반은 재요청이 아니라 차단이다
 * (fail-closed).
 *
 * <p><b>초안의 재요청은 1회 고정이다</b> (#238). 턴처럼 Provider 능력으로 갈리지 않는다 — 저 규칙
 * (R5.8 · R3.3)은 §5.2 의 <b>턴 출력 스키마</b>에 대한 것이고, 초안 계약은 그보다 훨씬 작다
 * (이름 둘과 글 둘). 그것을 두 번 놓치는 모델에게 세 번째를 주는 근거가 없고, 초안은 사용자가
 * <b>다시 누를 수 있는</b> 호출이다 — 턴처럼 세션 상태가 걸려 있지 않다.
 *
 * <p><b>시간 제한 안쪽에 놓인다</b> ({@code AiGatewayConfiguration}). §6.1 은 4단계(호출, 25s)와
 * 5단계(재요청)를 나란히 두고 §6.3 은 서버 전체 예산을 28s 로 못박는데, 재요청이 제한 밖이면
 * 두 번의 호출이 50s 가 되어 예산을 넘는다. 원문이 정하지 않은 지점이라
 * {@code docs/corrections.md} 에 올렸다.
 *
 * <p><b>그 사실을 값이 강제한다</b> (B-21-2). 재요청 전에 {@link GenerationBudget} 을 보고, 남은
 * 예산이 없으면 <b>호출을 걸지 않는다</b> — 이미 끝난 예산으로 호출을 걸어 놓고 취소하는 것보다
 * 걸지 않는 편이 싸다. 예산이 열려 있지 않으면(= 시간 제한 밖에 배선됐으면) 첫 호출에서 실패한다.
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
	 * 다시 보내 나아지는 종류의 실패가 아니고, 시간 초과는 이미 예산을 다 쓴 뒤다.
	 *
	 * <p><b>예산이 남지 않았으면 재요청 대신 {@link GenerationTimedOutException} 이다</b> (§13-19).
	 * 원인은 스키마인데 표시는 시간 초과가 되지만, 그 오분류는 §13-19 가 값을 정하며 이미 받아들인
	 * 대가다 — 반대쪽은 <b>사용자가 이미 끊은 뒤에도 도는 유료 호출</b>이고, 오분류는 로그에서
	 * 복구되지만 청구는 복구되지 않는다. 세션 상태는 그대로다 (R6.4, R6.6).
	 */
	@Override
	public GeneratedTurn generateTurn(TurnRequest request) {
		GenerationBudget budget = GenerationBudget.current();
		int allowedRetries = allowedRetries();

		TurnOutputSchemaException lastViolation = null;
		for (int attempt = 0; attempt <= allowedRetries; attempt++) {
			// 첫 호출은 예산을 확인하지 않는다 — 예산은 방금 열렸고, 확인해야 할 것은
			// 앞의 시도가 그것을 얼마나 썼는가다.
			if (attempt > 0 && budget.exhausted()) {
				throw new GenerationTimedOutException(budget.total());
			}
			try {
				// 재요청은 같은 턴의 별도 호출이다. 기록이 그것을 별개 행으로 남기려면
				// 어댑터가 지금이 몇 번째인지 알아야 한다 (B-25, R5.8 · R3.3).
				int attemptNo = attempt + 1;
				return AiCallAttempt.within(attemptNo, () -> this.delegate.generateTurn(request));
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

	/**
	 * <b>판정에는 재요청을 걸지 않는다</b> (B-30).
	 *
	 * <p>여기의 재요청 계약은 §5.2 <b>턴 출력</b> 스키마에 대한 것이다 (R5.8 · R3.3). 판정 응답이
	 * 형식을 어기면 그것은 <b>판정 실패</b>이고, 판정 실패의 처리는 재요청이 아니라 차단이다
	 * (fail-closed) — 판정하지 못한 응답을 사용자에게 보내지 않는 것이 요점이다 (I-2).
	 */
	@Override
	public Set<SafetyCategory> classifySafety(SafetyClassificationRequest request) {
		return this.delegate.classifySafety(request);
	}

	/**
	 * 계약을 못 맞춘 초안을 <b>한 번</b> 다시 요청한다 (#238).
	 *
	 * <p><b>개수 부족은 여기 오지 않는다.</b> 모자란 초안도 유효한 초안이며
	 * ({@link OutlineOutputFormat}), 그것을 재요청 사유로 삼으면 <b>모델이 짧게 답하는 날마다
	 * 비용이 두 배</b>가 된다. 여기 오는 것은 형태가 어긋난 응답뿐이다.
	 *
	 * <p><b>예산과 기록은 턴과 같은 규칙을 쓴다.</b> 남은 예산이 없으면 걸지 않고(B-21-2),
	 * 재요청은 별개의 호출이므로 몇 번째인지를 어댑터에 알린다 (B-25).
	 *
	 * <p><b>재요청까지 실패하면 마지막 위반을 그대로 올려 보낸다.</b> 턴은 시도 횟수를 담은
	 * seam 예외로 바꾸지만({@code OutputSchemaRejectedException}) 초안에는 그것을 읽고 다르게
	 * 행동하는 호출자가 없다 — 없는 계약을 미리 만들지 않는다.
	 */
	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		GenerationBudget budget = GenerationBudget.current();

		try {
			return AiCallAttempt.within(1, () -> this.delegate.draftOutline(request));
		}
		catch (OutlineOutputSchemaException first) {
			if (budget.exhausted()) {
				throw new GenerationTimedOutException(budget.total());
			}
			return AiCallAttempt.within(2, () -> this.delegate.draftOutline(request));
		}
	}
}
