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
 * <p>SQL 로 집계한다. 완주 세션을 엔티티로 다 읽어 메모리에서 세면 <b>작품이 인기를 얻을수록
 * 배치가 무거워진다</b> — 집계는 DB 가 가장 잘하는 일이다.
 */
@Component
public class CompletedSessionTally implements EndingReachSource {

	private final JdbcClient jdbc;

	public CompletedSessionTally(@Qualifier("playDataSource") DataSource playDataSource) {
		this.jdbc = JdbcClient.create(playDataSource);
	}

	@Override
	public List<EndingReach> tallyReached() {
		Map<UUID, Long> completedByStory = new HashMap<>();
		this.jdbc.sql("""
						SELECT story_id, COUNT(*) AS total FROM play_session
						WHERE status = 'completed' AND deleted_at IS NULL AND current_ending_id IS NOT NULL
						GROUP BY story_id
						""")
				.query((rs, rowNum) -> Map.entry(rs.getObject("story_id", UUID.class), rs.getLong("total")))
				.list()
				.forEach(entry -> completedByStory.put(entry.getKey(), entry.getValue()));

		List<EndingReach> reaches = new ArrayList<>();
		this.jdbc.sql("""
						SELECT story_id, current_ending_id, COUNT(*) AS reached FROM play_session
						WHERE status = 'completed' AND deleted_at IS NULL AND current_ending_id IS NOT NULL
						GROUP BY story_id, current_ending_id
						""")
				.query((rs, rowNum) -> new EndingReach(rs.getObject("story_id", UUID.class),
						rs.getObject("current_ending_id", UUID.class), rs.getLong("reached"), 0))
				.list()
				.forEach(row -> reaches.add(new EndingReach(row.storyId(), row.endingId(), row.reachedCount(),
						completedByStory.getOrDefault(row.storyId(), 0L))));
		return reaches;
	}
}
