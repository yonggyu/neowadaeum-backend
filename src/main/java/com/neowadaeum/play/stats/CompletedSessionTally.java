package com.neowadaeum.play.stats;

import com.neowadaeum.common.spi.EndingReach;
import com.neowadaeum.common.spi.EndingReachSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 완주 세션의 도달 집계 (B-39) — {@link EndingReachSource} 의 play 쪽 구현.
 *
 * <p><b>{@code ending_no} 를 모른다.</b> 세션이 들고 있는 것은 {@code ending_def} 의 식별자이며,
 * 그것이 그 작품의 몇 번째 엔딩인지는 catalog 의 지식이다 (§5.3) — 여기서 변환하려면 스키마를
 * 가로질러야 한다.
 *
 * <p><b>지운 세션과 완주하지 않은 세션은 세지 않는다.</b> 전자는 사용자가 없앤 것이고 후자는
 * 아직 결과가 아니다.
 *
 * <p><b>파기된 세션이 남긴 몫을 함께 센다</b> (§13-44, 이슈 #228). 이 집계는 매 회차 전량
 * 재계산이므로 세션이 사라지면 과거 도달률이 함께 줄어든다 — 탈퇴 한 건이 작품의 통계를
 * 움직이는 것을 막는 것이 {@link PurgedSessionTally} 다.
 *
 * <p><b>분모를 따로 세지 않는다.</b> 완주 세션 전체 수는 그 작품의 엔딩별 도달 수의 합이다 —
 * 같은 조건을 두 번 세면 살아 있는 행과 파기된 몫 중 한쪽을 빠뜨렸을 때 <b>분자와 분모가
 * 조용히 어긋난다.</b>
 *
 * <p>SQL 로 집계한다. 완주 세션을 엔티티로 다 읽어 메모리에서 세면 <b>작품이 인기를 얻을수록
 * 배치가 무거워진다</b> — 집계는 DB 가 가장 잘하는 일이다.
 */
@Component
public class CompletedSessionTally implements EndingReachSource {

	private final JdbcClient jdbc;

	private final PurgedSessionTally purged;

	public CompletedSessionTally(@Qualifier("playDataSource") DataSource playDataSource,
			PurgedSessionTally purged) {
		this.jdbc = JdbcClient.create(playDataSource);
		this.purged = purged;
	}

	@Override
	public List<EndingReach> tallyReached() {
		Map<ReachedEnding, Long> reached = new HashMap<>();
		this.jdbc.sql("""
						SELECT story_id, current_ending_id, COUNT(*) AS reached FROM play_session
						WHERE status = 'completed' AND deleted_at IS NULL AND current_ending_id IS NOT NULL
						GROUP BY story_id, current_ending_id
						""")
				.query((rs, rowNum) -> Map.entry(
						new ReachedEnding(rs.getObject("story_id", UUID.class),
								rs.getObject("current_ending_id", UUID.class)),
						rs.getLong("reached")))
				.list()
				.forEach(row -> reached.merge(row.getKey(), row.getValue(), Long::sum));
		this.purged.carried().forEach((key, count) -> reached.merge(key, count, Long::sum));

		Map<UUID, Long> completedByStory = new HashMap<>();
		reached.forEach((key, count) -> completedByStory.merge(key.storyId(), count, Long::sum));

		List<EndingReach> reaches = new ArrayList<>();
		reached.forEach((key, count) -> reaches.add(new EndingReach(key.storyId(), key.endingId(),
				count, completedByStory.getOrDefault(key.storyId(), 0L))));
		return reaches;
	}
}
