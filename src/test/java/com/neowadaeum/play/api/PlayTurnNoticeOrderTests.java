package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.catalog.query.StoryStatusView;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.web.IdempotencyStore;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SafetyVerdict;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §13-90 — <b>고지 문구가 없으면 모델을 부르기 전에 끝난다</b> (이슈 #437, R11.1).
 *
 * <p>결함은 <b>순서</b>였다. 문구 조회가 응답 조립 자리에 있었으므로, 설정이 비어 있으면 서버는
 * provider 를 부르고 턴과 스냅샷을 저장한 <b>다음</b> 500 을 돌려줬다 — <b>비용은 나가고 화면은
 * 실패하며</b>, 아무도 보지 못한 턴이 append-only 이력에 남았다 (I-5).
 *
 * <p><b>응답이 500 이라는 것만 보면 고치기 전에도 통과한다.</b> 그래서 여기서 단언하는 것은
 * 응답 코드가 아니라 <b>파이프라인이 불리지 않았다는 사실</b>이다.
 *
 * <p>뒤의 두 테스트는 <b>반대 방향</b>을 잠근다 — 문구 조회를 더 앞으로 밀면 소유권과 낙관적
 * 잠금 판정이 500 에 먹힌다. 그 자리가 "가장 이른 자리"의 경계다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class PlayTurnNoticeOrderTests {

	private static final UUID PLAYER_REF = UUID.fromString("22222222-2222-4222-8222-000000000001");

	private static final UUID SESSION_ID = UUID.fromString("33333333-3333-4333-8333-000000000001");

	private static final UUID STORY_ID = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID VERSION_ID = UUID.fromString("11111111-1111-4111-8111-000000000009");

	private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

	private static final String PARAGRAPHS = """
			[{"type":"NARRATION","text":"문 앞이다."}]""";

	private static final String CHOICES = """
			[{"choiceId":"1-1-abcdef","order":1,"text":"문을 연다","disabled":false}]""";

	private final PlaySessionRepository sessions = mock(PlaySessionRepository.class);

	private final TurnRepository turns = mock(TurnRepository.class);

	private final StoryVersionFacade storyVersions = mock(StoryVersionFacade.class);

	private final TurnPipeline pipeline = mock(TurnPipeline.class);

	private final TurnGuards guards = mock(TurnGuards.class);

	private final IdempotencyStore idempotency = mock(IdempotencyStore.class);

	private final StoryCatalogFacade stories = mock(StoryCatalogFacade.class);

	private final AiNoticeQuery notices = mock(AiNoticeQuery.class);

	private final PlayTurnService service = new PlayTurnService(this.sessions, this.turns, this.storyVersions,
			this.pipeline, this.guards, this.idempotency, this.stories, new AiNoticeText(this.notices));

	/** 턴 1 을 마친 진행 중 세션. 요청이 검증을 전부 통과하는 상태다. */
	@BeforeEach
	void anOwnedSessionReadyForTurnTwo() {
		PlaySession session = PlaySession.start(PLAYER_REF, STORY_ID, VERSION_ID, "fixed", "scenario", false, NOW);
		session.recordTurn(1, 1, NOW);

		given(this.sessions.findById(SESSION_ID)).willReturn(Optional.of(session));
		given(this.stories.status(STORY_ID)).willReturn(Optional.of(new StoryStatusView(VERSION_ID, "published")));
		given(this.turns.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(SESSION_ID))
				.willReturn(Optional.of(Turn.create(new Turn.TurnDraft(SESSION_ID, 1, 1, PARAGRAPHS, CHOICES,
						null, false, false, null, SafetyVerdict.PASS, true, false), NOW)));

		// 설정이 비어 있는 상태. `service_config` 의 행이므로 기동 검사(§13-89)를 지난 뒤에도
		// 이렇게 될 수 있다.
		given(this.notices.current()).willReturn(Optional.empty());
	}

	/**
	 * <b>이것이 이슈 #437 이다.</b> 문구가 없을 때 <b>모델이 불리지 않는다.</b>
	 *
	 * <p>파이프라인이 불리지 않으면 provider 호출도 턴·스냅샷 저장도 없다 — 저장은 그 안에서만
	 * 일어난다. 멱등 예약도 잡히지 않으므로 같은 선택이 TTL 동안 막히지도 않는다 (R6.2).
	 */
	@Test
	void S13_90_a_missing_notice_fails_before_the_model_is_called() {
		assertThatThrownBy(() -> this.service.advance(PLAYER_REF, SESSION_ID, request(1)))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.INTERNAL_ERROR);

		verifyNoInteractions(this.pipeline);
		verify(this.idempotency, never()).reserve(anyString());
		verify(this.guards, never()).acquireGenerationLock(any());
	}

	/**
	 * I-6 — <b>{@code turnNo} 불일치는 문구가 없어도 409 다.</b>
	 *
	 * <p>문구 조회를 낙관적 잠금 판정 앞으로 밀면 이 요청이 500 이 되고, 클라이언트는 자기가
	 * 무엇을 맞춰야 하는지 알 방법을 잃는다. 응답에 실리는 현재 턴 번호가 그 근거다 (R6.1).
	 */
	@Test
	void I6_a_turn_no_mismatch_still_answers_409_when_the_notice_is_missing() {
		assertThatThrownBy(() -> this.service.advance(PLAYER_REF, SESSION_ID, request(0)))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.TURN_CONFLICT);
	}

	/**
	 * 남의 세션은 <b>문구가 없어도</b> "없는 것"이다 (I-3).
	 *
	 * <p>소유권 판정이 뒤로 밀리면 존재하지 않는 세션과 남의 세션이 500 으로 뭉뚱그려진다 —
	 * 그 자체로 새는 것은 없지만, 요청을 거절할 이유가 있는데 운영 결함으로 답하게 된다.
	 */
	@Test
	void I3_someone_elses_session_is_still_not_found_when_the_notice_is_missing() {
		assertThatThrownBy(() -> this.service.advance(UUID.randomUUID(), SESSION_ID, request(1)))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	private static TurnRequestBody request(int turnNo) {
		return new TurnRequestBody("1-1-abcdef", turnNo, null);
	}
}
