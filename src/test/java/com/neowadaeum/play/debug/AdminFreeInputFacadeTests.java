package com.neowadaeum.play.debug;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.safety.l1.InputSafetyScreen;
import com.neowadaeum.safety.l1.InputVerdict;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * B-43 — <b>두 개의 문이 한 자리에 있다</b> (I-17, I-18, R14.1~R14.3).
 *
 * <p>세션 종류 판정과 L1 검수를 따로 두면 <b>한쪽만 지나는 호출자</b>가 생긴다. 여기서 확인하는
 * 것은 그 둘이 실제로 같은 길목에 있는지다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AdminFreeInputFacadeTests {

	private static final UUID SESSION_ID = UUID.randomUUID();

	private static final UUID STORY_ID = UUID.randomUUID();

	private final PlaySessionRepository sessions = mock(PlaySessionRepository.class);

	private final InputSafetyScreen screen = mock(InputSafetyScreen.class);

	private final TurnPipeline pipeline = mock(TurnPipeline.class);

	/** 계측은 이 테스트의 관심사가 아니다 — 값을 버리는 레지스트리로 배선만 채운다 (B-48). */
	private final AdminFreeInputFacade facade = new AdminFreeInputFacade(this.sessions, this.screen,
			this.pipeline, new com.neowadaeum.common.observability.SafetyMetrics(
					new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

	/**
	 * <b>사용자 소유 세션에는 넣지 못한다</b> (I-18, R14.3).
	 *
	 * <p>남의 이야기에 관리자가 문장을 넣는 것은 디버그가 아니라 개입이다.
	 */
	@Test
	void I18_a_user_owned_session_refuses_free_input() {
		givenSession(false);

		assertThatThrownBy(() -> this.facade.submit(SESSION_ID, "창밖을 본다"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
		verify(this.pipeline, never()).advanceWithFreeInput(any(), any());
	}

	/** 세션 종류를 먼저 본다 — 사용자 세션이면 <b>검수에 넣을 일도 없다.</b> */
	@Test
	void I18_a_user_owned_session_is_refused_before_screening() {
		givenSession(false);

		assertThatThrownBy(() -> this.facade.submit(SESSION_ID, "창밖을 본다"))
				.isInstanceOf(ApiException.class);
		verify(this.screen, never()).screen(any());
	}

	/** <b>관리자라는 사실이 검수를 면제하지 않는다</b> (I-17, R14.1). */
	@Test
	void I17_a_blocked_input_never_reaches_the_pipeline() {
		givenSession(true);
		given(this.screen.screen(any()))
				.willReturn(new InputVerdict(true, Set.of(SafetyCategory.REAL_PERSON_HARM)));

		assertThatThrownBy(() -> this.facade.submit(SESSION_ID, "무엇이든"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.SAFETY_BLOCKED);
		verify(this.pipeline, never()).advanceWithFreeInput(any(), any());
	}

	/** 지워진 세션은 없는 것과 같다. */
	@Test
	void R14_3_a_deleted_session_is_not_found() {
		PlaySession session = PlaySession.start(UUID.randomUUID(), STORY_ID, UUID.randomUUID(),
				"fixed", "scenario", true, Instant.now());
		session.deleteBy(Instant.now());
		given(this.sessions.findById(SESSION_ID)).willReturn(Optional.of(session));

		assertThatThrownBy(() -> this.facade.submit(SESSION_ID, "창밖을 본다"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	/** 없는 세션도 같다. */
	@Test
	void R14_3_an_unknown_session_is_not_found() {
		given(this.sessions.findById(SESSION_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.facade.submit(SESSION_ID, "창밖을 본다"))
				.isInstanceOf(ApiException.class);
	}

	/** 테스트 세션이고 검수를 지나면 <b>같은 파이프라인</b>으로 넘어간다. */
	@Test
	void R14_2_a_clean_input_on_a_test_session_reaches_the_pipeline() {
		givenSession(true);
		given(this.screen.screen(any())).willReturn(InputVerdict.pass());
		given(this.pipeline.advanceWithFreeInput(any(), any())).willReturn(
				new com.neowadaeum.play.orchestrator.TurnOutcome(
						com.neowadaeum.play.orchestrator.TurnOutcome.TurnStatus.GENERATED,
						UUID.randomUUID(), 3, false, 1, null, null, 0, Set.of()));

		this.facade.submit(SESSION_ID, "창밖을 본다");

		verify(this.pipeline).advanceWithFreeInput(SESSION_ID, "창밖을 본다");
	}

	private void givenSession(boolean testSession) {
		given(this.sessions.findById(SESSION_ID)).willReturn(Optional.of(PlaySession.start(
				UUID.randomUUID(), STORY_ID, UUID.randomUUID(), "fixed", "scenario", testSession,
				Instant.now())));
	}
}
