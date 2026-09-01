package com.neowadaeum.play.stats;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 파기된 완주 세션이 남긴 도달 집계 (§13-44, R2.7, R12.4, 이슈 #228).
 *
 * <p><b>왜 이것이 있는가.</b> 도달률 집계는 매 회차 살아 있는 세션을 <b>전량 재계산</b>한다
 * ({@link CompletedSessionTally}) — 누적 카운터가 아니다. 탈퇴 파기는 그 회원의 세션을 지운다
 * (B-61). 그대로 두면 <b>탈퇴 한 건이 그 사람이 완주했던 모든 작품의 도달률을 줄인다</b>.
 * 작품에 아무 변화가 없는데 숫자가 바뀌고, R2.8 의 표본 경계 근처에서는 도달률이 사라졌다
 * 나타난다.
 *
 * <p><b>§13-44 는 지우기 전에 더하기로 했다.</b> 원본은 파기하고, 기여분은 <b>개인과 다시 이을
 * 수 없는 합계</b>로만 남긴다. 이 표에는 {@code player_ref} 도 {@code session_id} 도 플레이
 * 시각도 없다 — 남는 것은 "이 작품의 이 엔딩에 몇 번 도달했다" 하나이고 그것은 작품에 대한
 * 사실이지 회원에 대한 사실이 아니다.
 *
 * <p><b>파기와 같은 트랜잭션에서 더한다.</b> 부르는 쪽이
 * {@code @Transactional("playTransactionManager")} 안에 있고 이 클래스가 같은
 * {@code playDataSource} 를 쓰므로, 커밋되지 않으면 더한 것도 없던 일이 된다 — 나뉘면
 * <b>더하고 못 지웠거나 지우고 못 더한</b> 상태가 남고 어느 쪽도 되돌릴 수 없다.
 *
 * <p><b>덮어쓰지 않고 더한다.</b> 파기는 여러 회차에 걸쳐 일어난다 — 덮어쓰면 앞 회차가 남긴
 * 몫이 사라진다.
 */
@Component
public class PurgedSessionTally {

	private final JdbcClient jdbc;

	private final Clock clock;

	public PurgedSessionTally(@Qualifier("playDataSource") DataSource playDataSource, Clock clock) {
		this.jdbc = JdbcClient.create(playDataSource);
		this.clock = clock;
	}

	/**
	 * 지워질 세션의 도달 몫을 집계로 옮긴다.
	 *
	 * <p><b>세는 조건이 {@link CompletedSessionTally} 와 같아야 한다.</b> 저쪽이 세지 않는 것을
	 * 여기서 옮기면 파기가 도달률을 <b>늘린다</b> — 지운 사용자가 남긴 것이 사라지지 않는 것을
	 * 넘어 없던 도달이 생긴다. 사용자가 지운 세션({@code deleted_at})과 완주하지 않은 세션은
	 * 그래서 여기서도 제외된다.
	 *
	 * @param playerRefs 파기 대상 회원
	 * @return 더해진 (작품, 엔딩) 조합의 수. 옮길 것이 없으면 0
	 */
	public int archive(Collection<UUID> playerRefs) {
		if (playerRefs.isEmpty()) {
			return 0;
		}
		return this.jdbc.sql("""
						INSERT INTO purged_session_tally (story_id, ending_id, reached_count, updated_at)
						SELECT story_id, current_ending_id, COUNT(*), :now FROM play_session
						WHERE player_ref IN (:playerRefs)
						  AND status = 'completed' AND deleted_at IS NULL AND current_ending_id IS NOT NULL
						GROUP BY story_id, current_ending_id
						ON CONFLICT (story_id, ending_id) DO UPDATE
						SET reached_count = purged_session_tally.reached_count + EXCLUDED.reached_count,
						    updated_at = EXCLUDED.updated_at
						""")
				.param("now", this.clock.instant().atOffset(java.time.ZoneOffset.UTC))
				.param("playerRefs", playerRefs)
				.update();
	}

	/** 파기된 세션이 남긴 몫. 아직 아무도 탈퇴하지 않았으면 빈 맵이다. */
	Map<ReachedEnding, Long> carried() {
		Map<ReachedEnding, Long> carried = new HashMap<>();
		this.jdbc.sql("SELECT story_id, ending_id, reached_count FROM purged_session_tally")
				.query((rs, rowNum) -> Map.entry(
						new ReachedEnding(rs.getObject("story_id", UUID.class),
								rs.getObject("ending_id", UUID.class)),
						rs.getLong("reached_count")))
				.list()
				.forEach(entry -> carried.put(entry.getKey(), entry.getValue()));
		return carried;
	}
}
