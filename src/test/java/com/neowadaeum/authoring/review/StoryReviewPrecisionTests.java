package com.neowadaeum.authoring.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>기록한 시각과 다시 읽은 시각이 같아야 한다</b> (§13-57).
 *
 * <p>{@code timestamptz} 는 마이크로초까지만 담는다. 그런데 {@code Instant.now()} 의 정밀도는
 * <b>플랫폼이 정한다</b> — macOS 는 마이크로초라 자르지 않아도 우연히 맞고, Linux 는 나노초라
 * 어긋난다. 그래서 이 결함은 <b>개발 기계에서 재현되지 않고 CI 에서만 드러났다.</b>
 *
 * <p>§13-57 이 이 시각을 응답에 싣기 시작했으므로, 어긋나면 <b>판정 응답이 돌려준 시각과 다음
 * 조회가 돌려주는 시각이 다르다.</b> 시계의 정밀도에 기대지 않고 여기서 못박는다.
 */
class StoryReviewPrecisionTests {

	@Test
	void S13_57_a_recorded_time_is_truncated_to_what_the_column_stores() {
		Instant nanos = Instant.parse("2026-09-03T09:54:45Z").plusNanos(61_947_630L);
		assertThat(nanos).isNotEqualTo(nanos.truncatedTo(ChronoUnit.MICROS));

		StoryReview review = StoryReview.of(UUID.randomUUID(), ReviewStage.HUMAN, ReviewVerdict.PASS,
				null, UUID.randomUUID(), null, nanos);

		assertThat(review.getReviewedAt()).isEqualTo(nanos.truncatedTo(ChronoUnit.MICROS));
	}
}
