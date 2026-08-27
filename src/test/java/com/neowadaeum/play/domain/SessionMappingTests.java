package com.neowadaeum.play.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.repository.GameStateSnapshotRepository;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-2 (#39) — 세 엔티티가 {@code play} 스키마에 실제로 매핑되는지 확인한다.
 *
 * <p>매핑 자체는 {@code hibernate.hbm2ddl.auto=validate} 가 부팅에서 이미 검증한다. 여기서 보는 것은
 * 그다음이다 — <b>값이 왕복하는가.</b> {@code jsonb} · {@code timestamptz} · UUID · 소문자 상태 표기는
 * 전부 "매핑은 맞는데 값이 달라지는" 자리다.
 *
 * <p>시각은 마이크로초 이하가 없는 값을 쓴다. PostgreSQL {@code timestamptz} 의 정밀도가 마이크로초라
 * 나노초를 넣으면 잘려 돌아오고, 그러면 이 테스트는 매핑이 아니라 반올림을 검사하게 된다.
 */
class SessionMappingTests extends ContainerTestBase {

	private static final Instant NOW = Instant.parse("2026-08-23T04:05:06Z");

	/** Boot 4 는 Jackson 3 을 쓴다. jsonb 는 키 순서·공백을 스스로 정규화하므로 문자열 비교는 못 한다. */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private TurnRepository turns;

	@Autowired
	private GameStateSnapshotRepository snapshots;

	@Autowired
	@Qualifier("playEntityManagerFactory")
	private EntityManagerFactory entityManagerFactory;

	/** I-4 — 생성 시 고정된 값들이 그대로 돌아온다. §13-6 상태 표기도 함께 본다. */
	@Test
	void I4_session_round_trips_with_its_pinned_provider_and_version() {
		UUID playerRef = UUID.randomUUID();
		PlaySession saved = this.sessions.save(newSession(playerRef));

		PlaySession found = this.sessions.findById(saved.getId()).orElseThrow();

		assertThat(found.getPlayerRef()).isEqualTo(playerRef);
		assertThat(found.getStoryVersionId()).isEqualTo(saved.getStoryVersionId());
		assertThat(found.getProviderId()).isEqualTo("fixed");
		assertThat(found.getModelId()).isEqualTo("scenario-v1");
		assertThat(found.getStatus()).isEqualTo(SessionStatus.ACTIVE);
		assertThat(found.getTurnNo()).isZero();
		assertThat(found.getChapterNo()).isEqualTo(1);
		assertThat(found.isTestSession()).isFalse();
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getUpdatedAt()).isEqualTo(NOW);
		assertThat(found.getExpiresAt()).isNull();
		assertThat(found.getCompletedAt()).isNull();
		assertThat(found.getDeletedAt()).isNull();
	}

	/** I-1 — 선택지는 서버가 발급한 {@code choiceId} 와 함께 턴 안에 저장된다. */
	@Test
	void I1_turn_round_trips_paragraphs_and_server_issued_choices() throws Exception {
		PlaySession session = this.sessions.save(newSession(UUID.randomUUID()));
		String paragraphs = """
				["복도 끝에서 발소리가 멈췄다.", "문이 반쯤 열려 있었다."]""";
		String choices = """
				[{"choiceId": "1-0-a1b2c3", "text": "문을 연다"}, {"choiceId": "1-1-d4e5f6", "text": "돌아선다"}]""";

		Turn saved = this.turns.save(Turn.create(new Turn.TurnDraft(session.getId(), 1, 1, paragraphs, choices,
				null, false, false, null, SafetyVerdict.PASS, true, false), NOW));
		Turn found = this.turns.findById(saved.getId()).orElseThrow();

		assertThat(JSON.readTree(found.getParagraphs())).isEqualTo(JSON.readTree(paragraphs));
		assertThat(JSON.readTree(found.getChoices())).isEqualTo(JSON.readTree(choices));
		assertThat(found.getSessionId()).isEqualTo(session.getId());
		// R11.2 — 저장된 사실이다. 응답을 만들 때 계산하지 않는다.
		assertThat(found.isAiGenerated()).isTrue();
		assertThat(found.getTurnNo()).isEqualTo(1);
		assertThat(found.isEnding()).isFalse();
		// §13-9 isPending — 아직 고르지 않은 마지막 턴이다.
		assertThat(found.getChosenChoiceId()).isNull();
		assertThat(found.getChosenAt()).isNull();
		// R9.3 — 판정이 함께 저장된다. I-2 의 기록이다.
		assertThat(found.getSafetyVerdict()).isEqualTo(SafetyVerdict.PASS);
		// R14.2 — 일반 턴은 자유입력이 아니다.
		assertThat(found.isAdminFreeInput()).isFalse();
		// R14.4 — 되돌려지지 않은 턴이다.
		assertThat(found.getDeletedAt()).isNull();
	}

	/**
	 * I-2 · R9.3 — 판정 없이 턴을 만들 수 없다. 기본값을 두면 검수 생략이 통과로 기록된다.
	 *
	 * <p>{@code TurnDraft} 의 컴팩트 생성자에서 걸린다 — 열 개 가까운 인자를 나열하는 대신 레코드로
	 * 묶었고(S-9-1), 검증도 그 자리로 옮겼다.
	 */
	@Test
	void I2_turn_cannot_be_created_without_a_safety_verdict() {
		assertThatThrownBy(() -> Turn.create(new Turn.TurnDraft(UUID.randomUUID(), 1, 1, "[]", "[]",
				null, false, false, null, null, true, false), NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** I-5 — 스냅샷은 GameState 를 통째로 담고, 저장 후 되돌아온 값이 같다. */
	@Test
	void I5_snapshot_round_trips_the_whole_game_state() throws Exception {
		PlaySession session = this.sessions.save(newSession(UUID.randomUUID()));
		String state = """
				{"chapter": 1, "turn": 1, "affinity": {"yuna": 10}, "flags": ["first_talk"]}""";

		GameStateSnapshot saved = this.snapshots.save(
				GameStateSnapshot.capture(session.getId(), 1, state, NOW));
		GameStateSnapshot found = this.snapshots.findById(saved.getId()).orElseThrow();

		assertThat(JSON.readTree(found.getState())).isEqualTo(JSON.readTree(state));
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getDeletedAt()).isNull();
	}

	/**
	 * §5.3 — 이 EMF 는 {@code play} 밖의 엔티티를 알지 못한다.
	 *
	 * <p>스캔 범위가 넓어지면 다른 스키마의 엔티티가 같은 영속성 단위에 들어오고, 그 순간 JPQL 한 줄로
	 * 크로스 스키마 조인이 가능해진다. FK 검증은 FK 만 보므로 이 경로를 잡지 못한다 —
	 * <b>여기가 그 유일한 감시 지점이다</b> (#20).
	 */
	@Test
	void S5_3_play_persistence_unit_contains_only_play_entities() {
		assertThat(this.entityManagerFactory.getMetamodel().getEntities())
				.isNotEmpty()
				.allSatisfy((entity) -> assertThat(entity.getJavaType().getPackageName())
						.as("%s 가 play 모듈 밖의 엔티티다", entity.getJavaType().getName())
						.startsWith("com.neowadaeum.play"))
				.extracting(EntityType::getName)
				.containsExactlyInAnyOrder("PlaySession", "Turn", "GameStateSnapshot", "StorySummary");
	}

	private static PlaySession newSession(UUID playerRef) {
		return PlaySession.start(playerRef, UUID.randomUUID(), UUID.randomUUID(), "fixed", "scenario-v1", false, NOW);
	}
}
