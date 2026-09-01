package com.neowadaeum.play.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.TestSessionStarter;
import com.neowadaeum.play.api.PlayTurnService;
import com.neowadaeum.play.api.TurnRequestBody;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-53 — <b>미리보기는 상한에서 끝난다</b> (R8.13).
 *
 * <p>상한을 클라이언트에 맡기면 그것은 상한이 아니라 <b>안내</b>다.
 */
class PreviewTurnLimitIntegrationTests extends ContainerTestBase {

	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID SEED_VERSION = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private static final UUID AUTHOR_REF = UUID.fromString("00000000-0000-4000-8000-0000000000f1");

	@Autowired
	private TestSessionStarter starter;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@Autowired
	private PlayTurnService turnService;

	@AfterEach
	void clear() {
		this.sessions.findAll().stream().filter(s -> AUTHOR_REF.equals(s.getPlayerRef()))
				.forEach(session -> {
					this.snapshots.findAll().stream()
							.filter(snapshot -> session.getId().equals(snapshot.getSessionId()))
							.forEach(this.snapshots::delete);
					this.turns.findAll().stream()
							.filter(turn -> session.getId().equals(turn.getSessionId()))
							.forEach(this.turns::delete);
					this.sessions.delete(session);
				});
	}

	/** 열리면 <b>테스트 세션이고 상한을 갖는다</b> (I-18, R8.13). */
	@Test
	void R8_13_a_preview_session_is_a_test_session_with_a_limit() {
		var preview = this.starter.start(AUTHOR_REF, SEED_STORY, SEED_VERSION, 3);

		assertThat(this.sessions.findById(preview.sessionId())).get().satisfies(session -> {
			assertThat(session.isTestSession()).isTrue();
			assertThat(session.getTurnLimit()).isEqualTo(3);
			assertThat(session.getTurnNo()).isEqualTo(1);
		});
	}

	/** <b>상한에 닿으면 서버가 막는다.</b> 세션은 살아 있다 — 기록은 계속 읽혀야 한다. */
	@Test
	void R8_13_the_limit_is_enforced_by_the_server() {
		var preview = this.starter.start(AUTHOR_REF, SEED_STORY, SEED_VERSION, 1);

		assertThatThrownBy(() -> this.turnService.advance(AUTHOR_REF, preview.sessionId(),
				new TurnRequestBody(firstChoiceIdOf(preview.sessionId()), 1, null)))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);

		assertThat(this.sessions.findById(preview.sessionId())).get()
				.extracting(PlaySession::getTurnNo).isEqualTo(1);
	}

	/** 상한이 없는 세션은 그대로다 — 기존 세션 전부가 그 상태다. */
	@Test
	void R8_13_a_session_without_a_limit_is_untouched() {
		UUID sessionId = this.sessions.save(PlaySession.start(AUTHOR_REF, SEED_STORY, SEED_VERSION,
				"fixed", "scenario", false, java.time.Instant.now())).getId();

		assertThat(this.sessions.findById(sessionId)).get().satisfies(session -> {
			assertThat(session.getTurnLimit()).isNull();
			assertThat(session.hasReachedTurnLimit()).isFalse();
		});
	}

	/** 저장된 선택지에서 첫 항목의 식별자를 꺼낸다 (I-1). */
	private String firstChoiceIdOf(UUID sessionId) {
		String choices = this.turns
				.findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(sessionId).orElseThrow()
				.getChoices();
		return tools.jackson.databind.json.JsonMapper.builder().build().readTree(choices).get(0)
				.path("choiceId").asString();
	}
}
