package com.neowadaeum.play.debug;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.orchestrator.TurnOutcome;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 턴 재생성 (§14, B-42).
 *
 * <p><b>되돌리고 다시 만든다.</b> 되돌리기(R14.4)를 그대로 쓰고, 생성은 §4.3 의 <b>같은
 * 파이프라인</b>을 탄다 — 관리자용 생성 경로를 따로 만들면 두 곳이 서서히 갈라지고, 그러면
 * 디버그에서 본 것이 사용자가 겪는 것과 달라진다.
 *
 * <p><b>같은 선택을 다시 쓴다.</b> 재생성은 "그 선택에서 다른 본문"을 보는 것이지 다른 선택을
 * 해 보는 것이 아니다 — 선택을 바꾸면 그것은 재생성이 아니라 새 진행이다. 선택 또한
 * <b>서버가 저장해 둔 값</b>에서 되찾는다 (I-1).
 *
 * <p><b>결과가 같을 수 있다.</b> AI 호출은 비결정적이지만 그것이 보장이 아니다 — 재생성이
 * 반드시 다른 본문을 준다고 약속하지 않는다.
 */
@Service
public class SessionRegenerateFacade {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final SessionRollbackFacade rollback;

	private final TurnPipeline pipeline;

	private final TurnRepository turns;

	public SessionRegenerateFacade(SessionRollbackFacade rollback, TurnPipeline pipeline,
			TurnRepository turns) {
		this.rollback = rollback;
		this.pipeline = pipeline;
		this.turns = turns;
	}

	/**
	 * 지목한 턴을 다시 만든다.
	 *
	 * <p><b>되돌리기가 먼저다.</b> 접기 전에 생성하면 같은 턴 번호가 둘이 되고, 그 순간
	 * {@code turn_no} 는 낙관적 잠금의 키이기를 그만둔다 (I-6).
	 *
	 * @throws ApiException {@code VALIDATION_ERROR} — 되돌릴 수 없는 지점
	 * @throws ApiException {@code INVALID_CHOICE} — 직전 턴에 선택 기록이 없다. 그 턴은
	 *     <b>아직 진행되지 않은 것</b>이므로 다시 만들 대상이 아니다
	 */
	public RegenerateResult regenerate(UUID sessionId, int turnNo) {
		String chosenChoiceId = chosenChoiceIdAt(sessionId, turnNo - 1);
		Integer chosenOrder = (chosenChoiceId != null)
				? choiceOrderAt(sessionId, turnNo - 1, chosenChoiceId) : null;

		this.rollback.rollbackTo(sessionId, turnNo - 1);
		TurnOutcome outcome = this.pipeline.advance(sessionId, chosenOrder, chosenChoiceId);
		return new RegenerateResult(outcome.turnNo(), outcome.chapterNo(),
				outcome.status() == TurnOutcome.TurnStatus.SAFETY_BLOCKED, outcome.ended());
	}

	/**
	 * 다시 만든 결과.
	 *
	 * <p><b>{@code TurnOutcome} 을 그대로 내주지 않는다.</b> 그것은 {@code play.orchestrator} 의
	 * 타입이며, 내주면 관리자 모듈이 파이프라인 내부에 묶인다.
	 *
	 * <p><b>본문을 담지 않는다.</b> 관리자가 다시 만든 본문을 보려면 디버그를 다시 부른다 —
	 * 여기서 함께 주면 이 경로가 <b>고치기</b>이면서 <b>보기</b>가 된다.
	 *
	 * @param blocked 세이프티에 걸려 저장되지 않았다 (I-2). 그 경우 세션은 되돌린 자리에 남는다
	 */
	public record RegenerateResult(int turnNo, int chapterNo, boolean blocked, boolean ended) {
	}

	/** 첫 턴에는 앞선 선택이 없다 — {@code null} 이 정상이다 (§4.2). */
	private String chosenChoiceIdAt(UUID sessionId, int previousTurnNo) {
		if (previousTurnNo < 1) {
			return null;
		}
		Turn previous = this.turns.findBySessionIdAndTurnNoAndDeletedAtIsNull(sessionId, previousTurnNo)
				.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR));
		String choiceId = previous.getChosenChoiceId();
		if (choiceId == null) {
			throw new ApiException(ErrorCode.INVALID_CHOICE);
		}
		return choiceId;
	}

	/** 순서는 <b>서버가 저장해 둔 선택지</b>에서 되찾는다 (I-1). */
	private int choiceOrderAt(UUID sessionId, int previousTurnNo, String choiceId) {
		Turn previous = this.turns.findBySessionIdAndTurnNoAndDeletedAtIsNull(sessionId, previousTurnNo)
				.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR));
		for (JsonNode choice : JSON.readTree(previous.getChoices())) {
			if (choiceId.equals(choice.path("choiceId").asString(null))) {
				return choice.path("order").asInt();
			}
		}
		throw new ApiException(ErrorCode.INVALID_CHOICE);
	}
}
