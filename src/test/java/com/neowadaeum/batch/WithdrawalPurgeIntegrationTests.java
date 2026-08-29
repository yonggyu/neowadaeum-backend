package com.neowadaeum.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.spi.PlayerDataPurge;
import com.neowadaeum.common.spi.WithdrawnAccounts;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B-61(2/2) — <b>탈퇴하면 회원과 기록을 잇는 고리가 끊긴다</b> (R12.4, R12.5).
 *
 * <p>{@code withdrawn} 은 상태일 뿐이고 그것만으로는 아무것도 지워지지 않는다. 여기서 확인하는
 * 것은 <b>실제로 끊겼는가</b>이며, 동시에 <b>끊으면 안 되는 것이 남아 있는가</b>다 — 동의 이력은
 * 법정 기간 동안 보관해야 하므로 (R12.4) "전부 지우는 구현"은 통과하면 안 된다.
 */
class WithdrawalPurgeIntegrationTests extends ContainerTestBase {

	private static final UUID STORY_ID = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final UUID VERSION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

	@Autowired
	private WithdrawnAccounts accounts;

	@Autowired
	private PlayerDataPurge playerData;

	@Autowired
	private UserRepository users;

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private RetentionBatch batch;

	@Autowired
	@Qualifier("identityDataSource")
	private DataSource identity;

	@Autowired
	@Qualifier("playDataSource")
	private DataSource play;

	private final List<UUID> createdUsers = new java.util.ArrayList<>();

	private final List<UUID> createdSessions = new java.util.ArrayList<>();

	/**
	 * <b>내가 만든 것만 치운다.</b>
	 *
	 * <p>컨테이너는 한 벌이고 테스트 클래스가 나눠 쓴다 — {@code deleteAll()} 은 남의 회원과
	 * 세션까지 지우고, 그 세션에 턴이 매달려 있으면 제약에 걸린다.
	 */
	@AfterEach
	void clear() {
		this.createdSessions.forEach(this::deleteSessionTree);
		this.createdSessions.clear();
		this.createdUsers.forEach(this::deleteUserTree);
		this.createdUsers.clear();
	}

	/** <b>매핑이 끊긴다</b> (R12.5) — 그것이 파기의 실질이다. */
	@Test
	void R12_5_a_withdrawn_account_loses_its_player_ref_mapping() {
		Withdrawn member = givenWithdrawnMember();

		assertThat(this.accounts.pendingPurge()).contains(member.playerRef());
		assertThat(this.accounts.purge(List.of(member.playerRef()))).isEqualTo(1);

		assertThat(this.users.findById(member.userId())).get().satisfies(user -> {
			assertThat(user.getPlayerRef()).isNull();
			assertThat(user.getPurgedAt()).isNotNull();
			// R12.1 이 식별정보로 지목한 값이다. 탈퇴한 회원에 대해 들고 있을 목적이 없다.
			assertThat(user.getBirthDate()).isNull();
		});
	}

	/**
	 * <b>소셜 연결도 사라진다</b> (R12.5).
	 *
	 * <p>남아 있으면 같은 계정으로 다시 로그인했을 때 <b>지운 회원으로 돌아간다</b> — 그러면
	 * 파기한 것은 이름표뿐이다.
	 */
	@Test
	void R12_5_the_social_account_link_is_gone() {
		Withdrawn member = givenWithdrawnMember();

		this.accounts.purge(List.of(member.playerRef()));

		assertThat(countIdentity("SELECT count(*) FROM oauth_identity WHERE user_id = ?",
				member.userId())).isZero();
	}

	/**
	 * <b>동의 이력은 남는다</b> (R12.4 — 법정 기간).
	 *
	 * <p>이것이 없으면 <b>"전부 지우는 구현"도 통과</b>하고, 그 구현은 약관 동의의 증빙을 통째로
	 * 지운다. 회원 행을 지우지 않는 이유가 바로 이 표다 — {@code consent_log} 가 그것을 FK 로
	 * 가리킨다.
	 */
	@Test
	void R12_4_the_consent_history_stays() {
		Withdrawn member = givenWithdrawnMember();

		this.accounts.purge(List.of(member.playerRef()));

		assertThat(countIdentity("SELECT count(*) FROM consent_log WHERE user_id = ?",
				member.userId())).isEqualTo(1);
		assertThat(this.users.findById(member.userId())).isPresent();
	}

	/** <b>플레이 기록은 지워진다</b> (R12.4) — 만료와 달리 남겨 둘 사용자가 없다. */
	@Test
	void R12_4_the_play_history_is_deleted() {
		Withdrawn member = givenWithdrawnMember();
		UUID sessionId = givenPlayHistory(member.playerRef());

		assertThat(this.playerData.purge(List.of(member.playerRef()))).isEqualTo(1);

		assertThat(this.sessions.findById(sessionId)).isEmpty();
		assertThat(countPlay("SELECT count(*) FROM turn WHERE session_id = ?", sessionId)).isZero();
		assertThat(countPlay("SELECT count(*) FROM game_state_snapshot WHERE session_id = ?",
				sessionId)).isZero();
		assertThat(countPlay("SELECT count(*) FROM story_summary WHERE session_id = ?", sessionId))
				.isZero();
	}

	/**
	 * <b>남의 기록은 그대로다.</b>
	 *
	 * <p>이것이 없으면 "세션 표를 비우는 구현"도 통과한다 — 그리고 그 구현은 탈퇴 한 건이
	 * 서비스 전체의 기록을 지우게 만든다.
	 */
	@Test
	void R12_4_another_members_play_history_stays() {
		Withdrawn member = givenWithdrawnMember();
		UUID otherSession = givenPlayHistory(UUID.randomUUID());

		this.playerData.purge(List.of(member.playerRef()));

		assertThat(this.sessions.findById(otherSession)).isPresent();
	}

	/** 탈퇴하지 않은 회원은 대상이 아니다 — 파기는 상태가 부르는 일이다. */
	@Test
	void R12_5_an_active_member_is_not_a_purge_target() {
		UUID playerRef = UUID.randomUUID();
		UUID userId = givenMember(playerRef);

		assertThat(this.accounts.pendingPurge()).doesNotContain(playerRef);
		assertThat(this.accounts.purge(List.of(playerRef))).isZero();
		assertThat(this.users.findById(userId)).get()
				.satisfies(user -> assertThat(user.getPlayerRef()).isEqualTo(playerRef));
	}

	/**
	 * <b>두 번 돌아도 한 번이다.</b>
	 *
	 * <p>배치는 매일 돈다. 이미 파기된 회원이 다시 세어지면 <b>파기 건수 로그가 의미를 잃고</b>,
	 * "어제 몇 명을 지웠나"에 답할 수 없게 된다.
	 */
	@Test
	void R12_5_purging_twice_does_not_count_twice() {
		Withdrawn member = givenWithdrawnMember();

		assertThat(this.accounts.purge(List.of(member.playerRef()))).isEqualTo(1);
		assertThat(this.accounts.pendingPurge()).doesNotContain(member.playerRef());
		assertThat(this.accounts.purge(List.of(member.playerRef()))).isZero();
	}

	/**
	 * <b>배치가 순서를 지킨다</b> (R12.4, R12.5).
	 *
	 * <p>매핑을 먼저 끊으면 play 는 무엇을 지워야 할지 알 수 없게 된다 — 그러면 기록은 남고
	 * 회원만 지워진 것으로 세어진다. 한 회차를 실제로 돌려 <b>둘 다</b> 사라지는지 본다.
	 */
	@Test
	void R12_4_one_batch_run_removes_both_the_history_and_the_mapping() {
		Withdrawn member = givenWithdrawnMember();
		UUID sessionId = givenPlayHistory(member.playerRef());

		this.batch.run();

		assertThat(this.sessions.findById(sessionId)).isEmpty();
		assertThat(this.users.findById(member.userId())).get()
				.satisfies(user -> assertThat(user.getPlayerRef()).isNull());
	}

	// ── 준비 ────────────────────────────────────────────────

	private record Withdrawn(UUID userId, UUID playerRef) {
	}

	private Withdrawn givenWithdrawnMember() {
		UUID playerRef = UUID.randomUUID();
		UUID userId = givenMember(playerRef);
		// **SQL 로 상태를 바꾼다.** 탈퇴 신청 경로는 아직 없다 (B-62). 여기서 확인하려는 것은
		// 탈퇴한 회원이 파기되는가이지 어떻게 탈퇴하는가가 아니다.
		identityJdbc().sql("UPDATE \"user\" SET status = 'withdrawn' WHERE id = ?")
				.param(userId).update();
		return new Withdrawn(userId, playerRef);
	}

	private UUID givenMember(UUID playerRef) {
		User user = this.users.saveAndFlush(
				User.register(playerRef, LocalDate.of(2000, 1, 1), Instant.now()));
		this.createdUsers.add(user.getId());

		identityJdbc().sql("""
						INSERT INTO oauth_identity (id, user_id, provider, subject, email_hash, created_at)
						VALUES (?, ?, 'google', ?, ?, ?)
						""")
				.params(UUID.randomUUID(), user.getId(), UUID.randomUUID().toString(),
						UUID.randomUUID().toString(), now())
				.update();
		identityJdbc().sql("""
						INSERT INTO consent_log (id, user_id, consent_type, version, agreed_at)
						VALUES (?, ?, 'tos', 'v1', ?)
						""")
				.params(UUID.randomUUID(), user.getId(), now())
				.update();
		return user.getId();
	}

	/**
	 * 세션 하나와 거기 매달린 것들. 파기가 <b>매달린 것까지</b> 지우는지 보려면 셋 다 필요하다.
	 *
	 * <p><b>기본값 없는 NOT NULL 을 전부 적는다.</b> {@code safety_verdict}(R9.3)와
	 * {@code is_ai_generated}(R11.2)는 <b>기본값을 두지 않기로 한 컬럼</b>이다 — 값이 조용히
	 * 채워지면 그것은 그 턴의 사실이 아니라 스키마의 사실이 된다.
	 */
	private UUID givenPlayHistory(UUID playerRef) {
		PlaySession session = this.sessions.saveAndFlush(PlaySession.start(playerRef, STORY_ID,
				VERSION_ID, "fixed", "scenario", false, Instant.now()));
		UUID sessionId = session.getId();
		this.createdSessions.add(sessionId);

		playJdbc().sql("""
						INSERT INTO turn (id, session_id, turn_no, chapter_no, paragraphs, choices,
								safety_verdict, is_ai_generated, created_at)
						VALUES (?, ?, 1, 1, '[]'::jsonb, '[]'::jsonb, 'pass', TRUE, ?)
						""")
				.params(UUID.randomUUID(), sessionId, now()).update();
		playJdbc().sql("""
						INSERT INTO game_state_snapshot (id, session_id, turn_no, state, created_at)
						VALUES (?, ?, 1, '{}'::jsonb, ?)
						""")
				.params(UUID.randomUUID(), sessionId, now()).update();
		playJdbc().sql("""
						INSERT INTO story_summary (id, session_id, upto_turn_no, summary_text,
								token_estimate, created_at)
						VALUES (?, ?, 1, 'summary', 1, ?)
						""")
				.params(UUID.randomUUID(), sessionId, now()).update();
		return sessionId;
	}

	// ── 뒷정리 · 조회 ────────────────────────────────────────

	private void deleteSessionTree(UUID sessionId) {
		playJdbc().sql("DELETE FROM story_summary WHERE session_id = ?").param(sessionId).update();
		playJdbc().sql("DELETE FROM game_state_snapshot WHERE session_id = ?").param(sessionId)
				.update();
		playJdbc().sql("DELETE FROM turn WHERE session_id = ?").param(sessionId).update();
		playJdbc().sql("DELETE FROM play_session WHERE id = ?").param(sessionId).update();
	}

	private void deleteUserTree(UUID userId) {
		identityJdbc().sql("DELETE FROM consent_log WHERE user_id = ?").param(userId).update();
		identityJdbc().sql("DELETE FROM ai_notice_impression WHERE user_id = ?").param(userId)
				.update();
		identityJdbc().sql("DELETE FROM oauth_identity WHERE user_id = ?").param(userId).update();
		identityJdbc().sql("DELETE FROM \"user\" WHERE id = ?").param(userId).update();
	}

	private long countIdentity(String sql, UUID id) {
		return identityJdbc().sql(sql).param(id).query(Long.class).single();
	}

	private long countPlay(String sql, UUID id) {
		return playJdbc().sql(sql).param(id).query(Long.class).single();
	}

	private JdbcClient identityJdbc() {
		return JdbcClient.create(this.identity);
	}

	private JdbcClient playJdbc() {
		return JdbcClient.create(this.play);
	}

	/** {@code JdbcClient} 에 {@code Instant} 를 그대로 넘기지 않는다. */
	private static java.time.OffsetDateTime now() {
		return Instant.now().atOffset(ZoneOffset.UTC);
	}
}
