package com.neowadaeum.play.debug;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.observability.SafetyMetrics;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.orchestrator.TurnOutcome;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.safety.l1.InputSafetyScreen;
import com.neowadaeum.safety.l1.InputVerdict;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 관리자 자유입력 (§14, R14.1~R14.3, B-43).
 *
 * <p><b>두 개의 문이 한 자리에 있다.</b> 세션 종류 판정(I-18)과 L1 검수(I-17)는 따로 두면 한쪽만
 * 지나는 호출자가 생긴다 — 자유입력이 들어오는 길은 <b>이 메서드 하나</b>여야 한다.
 *
 * <p><b>사용자 소유 세션에는 넣지 않는다</b> (I-18, R14.3). 남의 이야기에 관리자가 문장을 넣는
 * 것은 디버그가 아니라 개입이다 — 사용자 세션은 읽기 전용 디버그(B-41)까지다.
 *
 * <p><b>관리자라는 사실이 검수를 면제하지 않는다</b> (I-17, R14.1). 통로가 하나 있으면 그것이
 * 곧 그 서비스의 실제 등급이 된다.
 *
 * <p>생성은 §4.3 의 <b>같은 파이프라인</b>을 탄다 — 자유입력 전용 경로를 만들면 관리자가 재현한
 * 것이 사용자가 겪는 것과 달라진다.
 */
@Service
public class AdminFreeInputFacade {

	private final PlaySessionRepository sessions;

	private final InputSafetyScreen inputScreen;

	private final TurnPipeline pipeline;

	private final SafetyMetrics safetyMetrics;

	public AdminFreeInputFacade(PlaySessionRepository sessions, InputSafetyScreen inputScreen,
			TurnPipeline pipeline, SafetyMetrics safetyMetrics) {
		this.sessions = sessions;
		this.inputScreen = inputScreen;
		this.pipeline = pipeline;
		this.safetyMetrics = safetyMetrics;
	}

	/**
	 * 임의의 행동 문장으로 다음 턴을 만든다.
	 *
	 * @throws ApiException {@code NOT_FOUND} — 없거나 지워진 세션
	 * @throws ApiException {@code FORBIDDEN} — 테스트 세션이 아니다 (I-18)
	 * @throws ApiException {@code SAFETY_BLOCKED} — L1 에 걸렸다 (I-17). <b>어디에 걸렸는지는
	 *     알리지 않는다</b> — 그것이 곧 우회의 단서다 (S-6, S-11)
	 */
	public FreeInputResult submit(UUID sessionId, String action) {
		PlaySession session = this.sessions.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (session.getDeletedAt() != null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (!session.isTestSession()) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}

		InputVerdict verdict = this.inputScreen.screen(action);
		// B-48 — L1 의 유일한 길목이 여기다. 검수기 자체에 계측을 붙이면 UGC 검수(B-54)가
		// 붙는 날 두 호출자가 같은 카운터를 다르게 태그하게 된다.
		this.safetyMetrics.record("l1", verdict.blocked(), verdict.categories());
		if (verdict.blocked()) {
			throw new ApiException(ErrorCode.SAFETY_BLOCKED);
		}

		TurnOutcome outcome = this.pipeline.advanceWithFreeInput(sessionId, action);
		return new FreeInputResult(outcome.turnNo(), outcome.chapterNo(),
				outcome.status() == TurnOutcome.TurnStatus.SAFETY_BLOCKED, outcome.ended());
	}

	/**
	 * 자유입력의 결과.
	 *
	 * @param blocked <b>L2</b> 에 걸려 저장되지 않았다 (I-2). L1 에 걸린 경우는 예외로 끝나므로
	 *     여기 오지 않는다 — 둘은 다른 사건이다
	 */
	public record FreeInputResult(int turnNo, int chapterNo, boolean blocked, boolean ended) {
	}
}
