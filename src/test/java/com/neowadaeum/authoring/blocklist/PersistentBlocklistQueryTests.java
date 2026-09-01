package com.neowadaeum.authoring.blocklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.neowadaeum.common.spi.SafetyCategory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * B-49 — <b>캐시가 갱신을 삼키면 안 된다</b> (R9.4, ADR-0002).
 *
 * <p>등록한 항목이 다음 판정부터 걸리지 않으면, 운영자는 등록했다고 믿는데 서비스는 여전히
 * 통과시킨다 — 그것이 가장 나쁜 상태다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class PersistentBlocklistQueryTests {

	/** 가상의 이름. */
	private static final String FICTIONAL = "이나린";

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	private final BlocklistEntryRepository entries = mock(BlocklistEntryRepository.class);

	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

	private final PersistentBlocklistQuery query = new PersistentBlocklistQuery(this.entries,
			movingClock());

	/** 판정은 매 턴 일어난다 — 두 번 물어도 DB 는 한 번만 읽는다. */
	@Test
	void R9_4_the_snapshot_is_reused_within_its_lifetime() {
		givenEntries(row(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK));

		this.query.findAll();
		this.query.findAll();

		verify(this.entries, times(1)).findBySeverity(any());
	}

	/** 수명이 지나면 다시 읽는다 — 다른 인스턴스가 등록한 것을 그때 본다. */
	@Test
	void R9_4_the_snapshot_expires() {
		givenEntries(row(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK));
		this.query.findAll();

		this.now.set(NOW.plus(PersistentBlocklistQuery.SNAPSHOT_TTL).plusSeconds(1));
		this.query.findAll();

		verify(this.entries, times(2)).findBySeverity(any());
	}

	/** <b>등록하면 다음 조회가 곧바로 본다.</b> 수명을 기다리지 않는다. */
	@Test
	void R9_4_invalidating_makes_the_next_read_hit_the_store() {
		givenEntries(row(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK));
		this.query.findAll();

		this.query.invalidate();
		this.query.findAll();

		verify(this.entries, times(2)).findBySeverity(any());
	}

	/** <b>{@code warn} 은 판정으로 나가지 않는다</b> (§13-31) — 나가면 경고가 차단이 된다. */
	@Test
	void R2_5_only_blocking_entries_reach_the_judge() {
		givenEntries(row(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK));

		this.query.findAll();

		verify(this.entries).findBySeverity(BlocklistSeverity.BLOCK.columnValue());
	}

	/** 종류가 분류로 옮겨진다 — 판정기는 <b>왜 막는가</b>만 안다. */
	@Test
	void R2_5_a_kind_becomes_a_safety_category() {
		givenEntries(row(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK),
				row(BlocklistKind.IP_TITLE, "달빛기사단", BlocklistSeverity.BLOCK));

		assertThat(this.query.findAll())
				.extracting(com.neowadaeum.common.spi.BlocklistEntry::category)
				.containsExactlyInAnyOrder(SafetyCategory.REAL_PERSON_HARM,
						SafetyCategory.IP_REPLICATION);
	}

	/** <b>대조에 쓰는 것은 정규화 값이다</b> (R2.5) — 원문이 아니다. */
	@Test
	void R2_5_the_normalized_value_is_what_goes_out() {
		givenEntries(row(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK));

		assertThat(this.query.findAll())
				.singleElement()
				.extracting(com.neowadaeum.common.spi.BlocklistEntry::normalizedValue)
				.isEqualTo(com.neowadaeum.common.support.TextNormalizer.normalize(FICTIONAL));
	}

	/**
	 * <b>조회에 실패하면 예외가 그대로 올라간다</b> (ADR-0002 fail-closed).
	 *
	 * <p>판정기가 그것을 차단으로 다룬다. 여기서 삼키면 그 성질이 사라진다.
	 */
	@Test
	void ADR0002_a_failed_load_is_not_swallowed() {
		willThrow(new IllegalStateException("조회 실패")).given(this.entries).findBySeverity(any());

		assertThatThrownBy(this.query::findAll).isInstanceOf(IllegalStateException.class);
	}

	/**
	 * <b>실패했을 때 옛 스냅샷을 계속 쓰지 않는다.</b>
	 *
	 * <p>못 읽는 상태에서 오래된 목록으로 통과시키는 것은 "읽을 수 없다"를 "괜찮다"로 바꾸는
	 * 일이다.
	 */
	@Test
	void ADR0002_a_stale_snapshot_is_not_served_after_a_failure() {
		givenEntries(row(BlocklistKind.REAL_PERSON, FICTIONAL, BlocklistSeverity.BLOCK));
		this.query.findAll();

		this.now.set(NOW.plus(PersistentBlocklistQuery.SNAPSHOT_TTL).plusSeconds(1));
		willThrow(new IllegalStateException("조회 실패")).given(this.entries).findBySeverity(any());

		assertThatThrownBy(this.query::findAll).isInstanceOf(IllegalStateException.class);
	}

	private void givenEntries(BlocklistEntryRow... rows) {
		given(this.entries.findBySeverity(any())).willReturn(List.of(rows));
	}

	private static BlocklistEntryRow row(BlocklistKind kind, String value,
			BlocklistSeverity severity) {
		return BlocklistEntryRow.of(kind, value, severity, "test", NOW);
	}

	/** 시계를 테스트가 옮긴다 — 수명이 지나는 것을 실제로 기다릴 이유가 없다. */
	private Clock movingClock() {
		return new Clock() {
			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return PersistentBlocklistQueryTests.this.now.get();
			}
		};
	}
}
