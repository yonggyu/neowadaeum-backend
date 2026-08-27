package com.neowadaeum.catalog.stats;

import com.neowadaeum.catalog.domain.EndingStat;
import com.neowadaeum.catalog.repository.EndingStatRepository;
import com.neowadaeum.common.spi.EndingReach;
import com.neowadaeum.common.spi.EndingReachSource;
import com.neowadaeum.common.spi.EndingStatAggregation;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도달률 집계 (R2.7, I-20, B-39) — {@link EndingStatAggregation} 의 catalog 쪽 구현.
 *
 * <p><b>{@code endingId → ending_no} 변환이 여기서 일어난다.</b> play 는 세션이 도달한 엔딩의
 * 식별자만 알고, 그것이 몇 번째인지는 catalog 의 지식이다 (§5.3).
 *
 * <p><b>집계 키가 {@code (story_id, ending_no)} 인 이유</b> (§2.6) — {@code ending_id} 로 세면
 * 버전을 발행할 때마다 행이 새로 생겨 <b>같은 엔딩인데 도달률이 0 부터 다시 시작한다.</b>
 * 그래서 여러 버전의 같은 번호가 <b>한 행으로 합쳐진다.</b>
 *
 * <p><b>ADR-0003 — 적재를 이 모듈이 한다.</b> batch 가 적재하면 {@code batch → catalog} 의존이
 * 생겨 경계가 무너진다.
 */
@Component
public class EndingStatRefresher implements EndingStatAggregation {

	private final EndingReachSource source;

	private final EndingStatRepository stats;

	private final JdbcClient jdbc;

	private final Clock clock;

	public EndingStatRefresher(EndingReachSource source, EndingStatRepository stats,
			@Qualifier("catalogDataSource") DataSource catalogDataSource, Clock clock) {
		this.source = source;
		this.stats = stats;
		this.jdbc = JdbcClient.create(catalogDataSource);
		this.clock = clock;
	}

	@Override
	@Transactional("catalogTransactionManager")
	public int refresh() {
		Map<UUID, Integer> endingNumbers = endingNumbers();
		Map<StoryEnding, long[]> merged = new HashMap<>();

		for (EndingReach reach : this.source.tallyReached()) {
			Integer endingNo = endingNumbers.get(reach.endingId());
			if (endingNo == null) {
				// 정의가 사라진 엔딩이다. 버릴 수밖에 없다 — 번호를 붙일 근거가 없다.
				continue;
			}
			// 버전이 달라도 같은 번호면 한 행이다 (§2.6).
			long[] totals = merged.computeIfAbsent(new StoryEnding(reach.storyId(), endingNo),
					key -> new long[2]);
			totals[0] += reach.reachedCount();
			totals[1] = Math.max(totals[1], reach.storyCompletedCount());
		}

		Instant now = this.clock.instant();
		merged.forEach((key, totals) -> this.stats.save(
				EndingStat.of(key.storyId(), key.endingNo(), totals[0], totals[1], now)));
		return merged.size();
	}

	/** 집계 키 (§2.6). 엔티티의 {@code @IdClass} 를 맵 키로 쓰지 않는다 — 값 타입이 필요할 뿐이다. */
	private record StoryEnding(UUID storyId, int endingNo) {
	}

	/** 모든 버전의 {@code (id → ending_no)}. 한 번에 읽는다 — 도달 건마다 물으면 N+1 이다. */
	private Map<UUID, Integer> endingNumbers() {
		Map<UUID, Integer> numbers = new HashMap<>();
		this.jdbc.sql("SELECT id, ending_no FROM ending_def")
				.query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class), rs.getInt("ending_no")))
				.list()
				.forEach(entry -> numbers.put(entry.getKey(), entry.getValue()));
		return numbers;
	}
}
