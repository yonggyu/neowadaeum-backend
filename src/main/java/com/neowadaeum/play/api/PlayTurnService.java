package com.neowadaeum.play.api;

import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.catalog.query.StoryVersionView;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.orchestrator.TurnOutcome;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.OutputSchemaRejectedException;
import com.neowadaeum.play.port.ProviderCallFailedException;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.common.web.IdempotencyStore;
import com.neowadaeum.play.repository.TurnRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 턴 요청의 검증과 응답 조립 (§4.3 의 2 · 12 단계).
 *
 * <p>생성 자체는 {@link TurnPipeline} 이 한다. 여기가 하는 일은 <b>들어와도 되는 요청인지 판단하는
 * 것</b>과 <b>저장된 턴을 응답 형태로 바꾸는 것</b>이다.
 *
 * <p><b>실패는 8단계 이전에서 끝난다</b> (R6.6). 검증에서 거절하면 상태가 전혀 변하지 않으므로
 * "다른 선택하기"에 별도 API 가 필요 없다.
 */
@Service
public class PlayTurnService {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** §6.3 — 서버 전체 응답 예산. 진행 중인 요청을 기다리는 상한이기도 하다. */
	private static final Duration SERVER_BUDGET = Duration.ofSeconds(28);

	private final PlaySessionRepository sessions;
	private final TurnRepository turns;
	private final StoryVersionFacade storyVersions;
	private final TurnPipeline pipeline;
	private final TurnGuards guards;
	private final IdempotencyStore idempotency;

	public PlayTurnService(PlaySessionRepository sessions, TurnRepository turns,
			StoryVersionFacade storyVersions, TurnPipeline pipeline, TurnGuards guards,
			IdempotencyStore idempotency) {
		this.sessions = sessions;
		this.turns = turns;
		this.storyVersions = storyVersions;
		this.pipeline = pipeline;
		this.guards = guards;
		this.idempotency = idempotency;
	}

	/**
	 * 다음 턴을 만든다 (§4.3).
	 *
	 * @throws ApiException 검증 실패. 이 경우 세션 상태는 그대로다 (R6.6)
	 */
	public TurnView advance(UUID playerRef, UUID sessionId, TurnRequestBody request) {
		PlaySession session = requireOwnedActiveSession(playerRef, sessionId);

		// R6.1 — turnNo 불일치는 409 이며 현재 턴 상태를 함께 준다. 클라이언트가 맞출 근거가 있어야 한다.
		if (session.getTurnNo() != request.turnNo()) {
			throw new ApiException(ErrorCode.TURN_CONFLICT,
					Map.of("currentTurnNo", session.getTurnNo(), "chapterNo", session.getChapterNo()));
		}

		// R6.5 — 서버가 강제하는 쿨다운. 클라이언트 쿨다운과 별개다.
		this.guards.requireNotCoolingDown(sessionId);

		// §15 — 분당·일일 한도 (B-38). 멱등 예약보다 **앞**이다: 한도에 걸린 요청이 예약을
		// 잡으면 그 키가 TTL 동안 남아, 한도가 풀린 뒤에도 같은 요청이 막힌다.
		this.guards.requireWithinLimits(playerRef);

		int chosenOrder = resolveChoiceOrder(sessionId, request.choiceId());
		String key = idempotencyKey(playerRef, sessionId, request);

		// R6.2 — 같은 요청이 이미 진행 중이면 기다렸다 그 결과를 준다. 409 를 주면 클라이언트가
		// 다시 눌러 결국 두 번 생성된다 — 보호 대상은 중복 과금이다.
		if (!this.idempotency.reserve(key)) {
			return this.idempotency.awaitResult(key, SERVER_BUDGET)
					.map(json -> JSON.readValue(json, TurnView.class))
					.orElseThrow(() -> new ApiException(ErrorCode.CONCURRENT_GENERATION));
		}

		return generate(playerRef, session, sessionId, chosenOrder, request.choiceId(), key);
	}

	/**
	 * 실제 생성. 락과 실패 카운터가 여기를 감싼다.
	 *
	 * <p><b>{@code finally} 에서 락을 푼다.</b> 실패 경로에서 빠뜨리면 그 계정은 TTL 이 지날 때까지
	 * 아무것도 못 한다.
	 */
	private TurnView generate(UUID playerRef, PlaySession session, UUID sessionId, int chosenOrder,
			String chosenChoiceId, String key) {
		// §4.3-2 — 동시 생성 락(계정당 1개).
		this.guards.acquireGenerationLock(playerRef);
		try {
			TurnOutcome outcome = this.pipeline.advance(sessionId, chosenOrder, chosenChoiceId);
			if (outcome.status() == TurnOutcome.TurnStatus.SAFETY_BLOCKED) {
				throw safetyBlocked(outcome);
			}

			TurnView view = view(session.getStoryVersionId(), outcome);
			this.idempotency.complete(key, JSON.writeValueAsString(view));
			this.guards.recordSuccess(sessionId);
			return view;
		}
		catch (GenerationTimedOutException ex) {
			// R6.4 — 세션은 직전 턴 상태 그대로다. §4.3 의 8단계 이전에서 끊겼다.
			failed(sessionId, key);
			throw new ApiException(ErrorCode.GENERATION_TIMEOUT);
		}
		catch (OutputSchemaRejectedException | ProviderCallFailedException ex) {
			// R5.8 — 재요청까지 스키마를 못 맞췄거나(전자) 호출 자체가 실패했다(후자, B-22).
			// 둘 다 시간 초과와 같은 자리에서 끊기므로 상태는 그대로다 (R6.6).
			failed(sessionId, key);
			throw new ApiException(ErrorCode.PROVIDER_ERROR);
		}
		catch (RuntimeException ex) {
			failed(sessionId, key);
			throw ex;
		}
		finally {
			this.guards.releaseGenerationLock(playerRef);
		}
	}

	/**
	 * 실패를 기록하고 멱등 자리를 비운다.
	 *
	 * <p>비우지 않으면 <b>같은 선택으로는 영영 재시도할 수 없다.</b> 실패는 결과가 아니다.
	 */
	private void failed(UUID sessionId, String key) {
		this.idempotency.release(key);
		this.guards.recordFailure(sessionId);
	}

	/**
	 * R6.2 — 동일 {@code (sessionId, turnNo, choiceId)} 가 식별자다.
	 *
	 * <p><b>{@code playerRef} 를 넣는다.</b> 없으면 다른 사람의 결과를 받을 수 있는 키가 된다.
	 * 클라이언트가 보낸 {@code Idempotency-Key} 헤더는 있으면 함께 넣는다 — 같은 선택을 의도적으로
	 * 다시 하려는 경우를 구분할 수 있어야 한다.
	 */
	private static String idempotencyKey(UUID playerRef, UUID sessionId, TurnRequestBody request) {
		return "idem:%s:%s:%d:%s:%s".formatted(playerRef, sessionId, request.turnNo(), request.choiceId(),
				(request.idempotencyKey() != null) ? request.idempotencyKey() : "");
	}

	/** 세션 시작 직후의 첫 턴을 응답으로 바꾼다 (§4.2). */
	public TurnView view(UUID storyVersionId, TurnOutcome outcome) {
		if (outcome.status() == TurnOutcome.TurnStatus.SAFETY_BLOCKED) {
			throw safetyBlocked(outcome);
		}

		Turn turn = this.turns.findById(outcome.turnId())
				.orElseThrow(() -> new IllegalStateException("생성된 턴을 찾지 못했다: " + outcome.turnId()));
		StoryVersionView version = this.storyVersions.findByVersionId(storyVersionId)
				.orElseThrow(() -> new IllegalStateException("작품 버전을 찾지 못했다: " + storyVersionId));

		return toView(turn, version, outcome.endingIndex(),
				turn.isEnding() ? outcome.totalEndings() : null);
	}

	/**
	 * 마지막 턴을 다시 그린다 (§13.4 의 {@code GET /sessions/{id}/current}).
	 *
	 * <p><b>진행 중이 아닌 세션도 답한다.</b> 화면을 다시 그리는 요청이며, 끝난 이야기의 마지막
	 * 장면을 보여 주지 못하면 엔딩 화면이 새로고침에서 사라진다.
	 *
	 * <p>엔딩 순번은 <b>저장된 값이 아니라 지금 계산한다</b> — 턴에 남는 것은 어떤 엔딩에
	 * 도달했는가이고, 그것이 비시크릿 중 몇 번째인지는 작품 버전이 정한다 (R7.11).
	 */
	public TurnView current(UUID playerRef, UUID sessionId) {
		PlaySession session = requireOwnedSession(playerRef, sessionId);
		Turn turn = this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		StoryVersionView version = this.storyVersions.findByVersionId(session.getStoryVersionId())
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

		List<StoryVersionView.EndingView> visible = version.endings().stream()
				.filter(ending -> !ending.secret())
				.sorted(java.util.Comparator.comparingInt(StoryVersionView.EndingView::endingNo))
				.toList();

		return toView(turn, version, endingIndexOf(visible, turn.getEndingId()),
				turn.isEnding() ? visible.size() : null);
	}

	/**
	 * R7.11 — 비시크릿 중 1-based 순번. <b>시크릿 엔딩이면 {@code null}</b> 이다.
	 *
	 * <p>셀 자리가 없는데 번호를 주면 시크릿을 숨긴 목적이 깨진다.
	 */
	private static Integer endingIndexOf(List<StoryVersionView.EndingView> visible, UUID endingId) {
		if (endingId == null) {
			return null;
		}
		for (int index = 0; index < visible.size(); index++) {
			if (visible.get(index).id().equals(endingId)) {
				return index + 1;
			}
		}
		return null;
	}

	private static TurnView toView(Turn turn, StoryVersionView version, Integer endingIndex,
			Integer totalEndings) {
		return new TurnView(
				turn.getTurnNo(),
				turn.getChapterNo(),
				chapterTitle(version, turn.getChapterNo()),
				turn.isChapterChanged(),
				progressHint(version, turn.getChapterNo()),
				turn.getSpeakerName(),
				readParagraphs(turn.getParagraphs()),
				readChoices(turn.getChoices()),
				turn.isEnding(),
				turn.getEndingId(),
				endingIndex,
				totalEndings,
				// R11.2 — 저장된 사실을 그대로 읽는다. 여기서 판단하지 않는다.
				turn.isAiGenerated());
	}

	/**
	 * 소유 확인만 한다 — 상태는 보지 않는다.
	 *
	 * <p>{@code requireOwnedActiveSession} 과 나눈 이유는 <b>다시 그리기가 진행 중을 요구하지
	 * 않기 때문</b>이다. 끝난 세션의 마지막 장면은 여전히 보여 줘야 한다.
	 */
	private PlaySession requireOwnedSession(UUID playerRef, UUID sessionId) {
		PlaySession session = this.sessions.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!session.getPlayerRef().equals(playerRef)) {
			// 남의 세션은 "없는 것"과 구분되지 않아야 한다 (I-3).
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return session;
	}

	// ── 검증 ────────────────────────────────────────────────

	private PlaySession requireOwnedActiveSession(UUID playerRef, UUID sessionId) {
		PlaySession session = this.sessions.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

		// 남의 세션은 "없는 것"과 구분되지 않아야 한다 — 존재 여부가 새면 세션 id 를 훑을 수 있다.
		if (!session.getPlayerRef().equals(playerRef)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (session.getStatus() != SessionStatus.ACTIVE) {
			// §4.3-2 는 이 경우의 코드를 명시하지 않는다. 종료·만료된 세션에 턴을 더할 수 없다는
			// 뜻이므로 FORBIDDEN 으로 둔다 — TURN_CONFLICT 는 "현재 턴으로 맞춰 주세요"라서
			// 맞출 턴이 없는 상황에 맞지 않는다. PR 에 명시했다.
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		return session;
	}

	/**
	 * I-1 — {@code choiceId} 는 <b>직전 턴이 발급한 것</b>이어야 한다.
	 *
	 * <p>클라이언트가 보낸 텍스트를 신뢰하지 않으므로, 순서 또한 서버가 저장해 둔 값에서 되찾는다.
	 * 이전 턴의 식별자를 재전송해도 여기서 걸린다 — {@code choiceId} 에 턴 번호가 들어 있다 (§13-9).
	 */
	private int resolveChoiceOrder(UUID sessionId, String choiceId) {
		Turn lastTurn = this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_CHOICE));

		for (JsonNode choice : JSON.readTree(lastTurn.getChoices())) {
			if (choiceId.equals(choice.path("choiceId").asString(null))) {
				// I-11 — disabled 판정은 서버가 한다. P0 에서는 항상 false 지만 확인 경로는 둔다.
				if (choice.path("disabled").asBoolean(false)) {
					throw new ApiException(ErrorCode.INVALID_CHOICE);
				}
				return choice.path("order").asInt();
			}
		}
		throw new ApiException(ErrorCode.INVALID_CHOICE);
	}

	// ── 조립 ────────────────────────────────────────────────

	/**
	 * §9.3 차단 응답.
	 *
	 * <p><b>{@code retry} 를 넣지 않는다</b> (R9.5). 동일 입력이므로 같은 결과가 나와 무한 루프가 된다.
	 * <b>차단 사유를 담지 않는다</b> (R9.6) — 어떤 표현이 걸렸는지 알려주면 우회 학습을 돕는다.
	 * 카테고리는 서버 내부 기록으로만 남는다.
	 */
	private static ApiException safetyBlocked(TurnOutcome outcome) {
		return new ApiException(ErrorCode.SAFETY_BLOCKED, Map.of(
				"turnNo", outcome.turnNo(),
				"recoverable", true,
				"actions", List.of("choose_other", "leave")));
	}

	private static String chapterTitle(StoryVersionView version, int chapterNo) {
		return version.chapters().stream()
				.filter(chapter -> chapter.chapterNo() == chapterNo)
				.map(StoryVersionView.ChapterView::title)
				.findFirst()
				.orElse(null);
	}

	/** R7.5 — "Chapter 2 / 전체 3장". {@code progressPercent} 는 만들지 않는다. */
	private static String progressHint(StoryVersionView version, int chapterNo) {
		return "Chapter %d / 전체 %d장".formatted(chapterNo, version.chapters().size());
	}

	/**
	 * 저장된 문단 배열을 응답 형태로 읽는다 (#84).
	 *
	 * <p><b>{@code type} 을 소문자로 내보낸다.</b> 저장은 열거형 이름({@code NARRATION})이고 응답
	 * 계약은 §5.2 표기({@code "narration"})다 — 둘을 같은 값으로 두면 저장 형식을 바꿀 때 응답이
	 * 함께 바뀐다.
	 */
	private static List<TurnView.Paragraph> readParagraphs(String json) {
		List<TurnView.Paragraph> paragraphs = new ArrayList<>();
		JSON.readTree(json).forEach(node -> paragraphs.add(new TurnView.Paragraph(
				node.path("type").asString("NARRATION").toLowerCase(Locale.ROOT),
				node.path("speakerName").asString(null),
				node.path("text").asString(null))));
		return paragraphs;
	}

	private static List<TurnView.Choice> readChoices(String json) {
		List<TurnView.Choice> choices = new ArrayList<>();
		JSON.readTree(json).forEach(node -> choices.add(new TurnView.Choice(
				node.path("choiceId").asString(null),
				node.path("order").asInt(),
				node.path("text").asString(null),
				node.path("disabled").asBoolean(false),
				node.path("disabledReason").asString(null))));
		return choices;
	}
}
