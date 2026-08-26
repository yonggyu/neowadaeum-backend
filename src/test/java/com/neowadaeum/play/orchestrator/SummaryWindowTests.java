package com.neowadaeum.play.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 언제 · 어디까지 요약으로 옮기는가 (R4.5, §13-2, B-34).
 *
 * <p>컨테이너가 필요 없다 — 규칙 자체는 DB 도 Provider 도 보지 않는다 (ADR-0001).
 */
class SummaryWindowTests {

	private static final int MERGE = 8;

	/** 완충 구간 안이면 옮길 것이 없다. 8턴까지는 전부 프롬프트에 직접 실린다 (R4.7). */
	@Test
	void R4_5_nothing_moves_while_the_session_is_inside_the_buffer() {
		assertThat(SummaryWindow.of(1, 0, MERGE)).isEmpty();
		assertThat(SummaryWindow.of(8, 0, MERGE)).isEmpty();
	}

	/** 완충 구간을 넘긴 첫 턴에서 1턴 하나가 옮겨진다. */
	@Test
	void R4_5_the_first_turn_past_the_buffer_moves_exactly_one_turn() {
		assertThat(SummaryWindow.of(9, 0, MERGE)).contains(new SummaryWindow(1, 1));
	}

	/** 요약이 이미 있으면 그 뒤부터다. 같은 구간을 두 번 압축하면 결과는 같고 비용만 두 배다. */
	@Test
	void R4_5_an_existing_summary_moves_the_window_forward() {
		assertThat(SummaryWindow.of(20, 5, MERGE)).contains(new SummaryWindow(6, 12));
	}

	/** 이미 여기까지 요약돼 있으면 아무 일도 하지 않는다. */
	@Test
	void R4_5_an_up_to_date_summary_has_nothing_to_move() {
		assertThat(SummaryWindow.of(20, 12, MERGE)).isEmpty();
		assertThat(SummaryWindow.of(20, 15, MERGE)).as("요약이 앞서 있어도 되짚지 않는다").isEmpty();
	}

	/**
	 * <b>완충 구간이 설정값이다</b> (§13-2).
	 *
	 * <p>B-46 실측 후 조정될 값이라 상수로 박혀 있으면 그 조정이 배포가 된다. 여기서 확인하는 것은
	 * <b>바뀐 값이 실제로 경계를 옮기는가</b>다.
	 */
	@Test
	void S13_2_the_buffer_size_is_the_configured_one() {
		assertThat(SummaryWindow.of(9, 0, 4)).contains(new SummaryWindow(1, 5));
		assertThat(SummaryWindow.of(9, 0, 12)).isEmpty();
	}

	/** 여러 턴이 한 번에 밀려도 한 구간으로 묶인다 — 요약 호출은 턴당 최대 한 번이다. */
	@Test
	void R4_6_a_backlog_is_moved_as_one_window() {
		assertThat(SummaryWindow.of(30, 0, MERGE)).contains(new SummaryWindow(1, 22));
	}
}
