package com.neowadaeum.play.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.StorySummaryRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-34 (#118) — 요약 저장소의 두 성질을 확인한다: <b>append-only</b> 와 <b>현재 요약의 정의</b>.
 *
 * <p>둘 다 마이그레이션과 매핑이 함께 성립해야 하는 것이라 실제 DB 에 대고 본다 (ADR-0001 의
 * container 분류).
 */
class StorySummaryMappingTests extends ContainerTestBase {

	private static final Instant NOW = Instant.parse("2026-08-26T04:05:06Z");

	@Autowired
	private PlaySessionRepository sessions;

	@Autowired
	private StorySummaryRepository summaries;

	private UUID session() {
		return this.sessions.save(PlaySession.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				"fixed", "scenario-v1", false, NOW)).getId();
	}

	/** 저장한 값이 그대로 돌아온다. */
	@Test
	void R4_5_a_summary_round_trips() {
		UUID sessionId = session();

		UUID id = this.summaries.save(StorySummary.of(sessionId, 8, "지난 줄거리", 42, NOW)).getId();
		StorySummary found = this.summaries.findById(id).orElseThrow();

		assertThat(found.getSessionId()).isEqualTo(sessionId);
		assertThat(found.getUptoTurnNo()).isEqualTo(8);
		assertThat(found.getSummaryText()).isEqualTo("지난 줄거리");
		assertThat(found.getTokenEstimate()).isEqualTo(42);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getDeletedAt()).isNull();
	}

	/**
	 * <b>R2.6 — 재압축은 같은 턴 번호로 새 행을 남긴다.</b>
	 *
	 * <p>{@code (session_id, upto_turn_no)} 에 유일성을 걸었다면 여기서 막혔을 것이고, 그러면
	 * 재압축이 UPDATE 로 돌아가 append-only 가 깨진다 (R14.4 의 롤백이 되돌릴 대상을 잃는다).
	 */
	@Test
	void R2_6_a_recompression_appends_instead_of_overwriting() {
		UUID sessionId = session();

		this.summaries.save(StorySummary.of(sessionId, 8, "긴 요약", 800, NOW));
		this.summaries.save(StorySummary.of(sessionId, 8, "짧은 요약", 300, NOW.plusSeconds(1)));

		assertThat(this.summaries.findAll().stream().filter(s -> s.getSessionId().equals(sessionId)))
				.as("덮어썼다면 하나만 남는다")
				.hasSize(2);
	}

	/**
	 * <b>현재 요약 = 살아 있는 행 중 가장 최근.</b>
	 *
	 * <p>턴 번호가 먼저이고 기록 시각이 그다음이다 — 재압축이 같은 번호로 새 행을 남기므로 그
	 * 경우의 승자는 나중에 쓰인 쪽이다.
	 */
	@Test
	void R4_5_the_current_summary_is_the_latest_live_row() {
		UUID sessionId = session();

		this.summaries.save(StorySummary.of(sessionId, 8, "8턴까지", 100, NOW));
		this.summaries.save(StorySummary.of(sessionId, 16, "16턴까지 (긴 것)", 800, NOW.plusSeconds(1)));
		this.summaries.save(StorySummary.of(sessionId, 16, "16턴까지 (재압축)", 300, NOW.plusSeconds(2)));

		StorySummary current = this.summaries
				.findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(sessionId)
				.orElseThrow();

		assertThat(current.getSummaryText()).isEqualTo("16턴까지 (재압축)");
		assertThat(current.getTokenEstimate()).isEqualTo(300);
	}

	/** 요약이 하나도 없는 세션은 비어 있다 — 첫 턴들이 그 상태다. */
	@Test
	void R4_5_a_fresh_session_has_no_summary() {
		assertThat(this.summaries
				.findFirstBySessionIdAndDeletedAtIsNullOrderByUptoTurnNoDescCreatedAtDesc(session()))
				.isEmpty();
	}
}
