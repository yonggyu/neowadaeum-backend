package com.neowadaeum.catalog.publish;

import com.neowadaeum.common.spi.WithdrawnAuthorContent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴한 작성자의 작품 처리 (R12.5, §13-9, B-62).
 *
 * <p><b>지우지 않고 내린다.</b> 작품에는 그것을 플레이한 사람들의 기록이 매달려 있다 — 세션과
 * 도달률은 작성자의 것이 아니라 <b>플레이한 사람들의 것</b>이다. 지우면 남의 기록까지 사라진다.
 *
 * <p><b>{@code public} 만 강등한다.</b> {@code unlisted} 는 이미 목록에 없고 {@code private} 는
 * 애초에 보이지 않는다 — 그 둘을 건드리면 <b>바뀐 것이 없는데 바뀐 것으로 세어진다.</b>
 *
 * <p><b>검수 상태는 그대로 둔다.</b> 승인은 작품에 대한 판정이지 작성자에 대한 판정이 아니며,
 * 되돌리면 그 작품은 <b>다시 검수 큐에 올라</b> 사람의 시간을 쓴다.
 *
 * <p><b>원고는 지운다.</b> 발행되지 않은 원고는 작성자만 보는 비공개 저작물이고, 그것을 볼
 * 사람이 없어진 뒤에도 남겨 둘 근거가 없다 (R12.4).
 */
@Service
public class WithdrawnAuthorContentHandler implements WithdrawnAuthorContent {

	/**
	 * §13-9 기본 채택안의 표기.
	 *
	 * <p><b>이름을 비우지 않고 바꾼다.</b> 비우면 화면은 "작성자 없음"을 보여 주고, 그것은
	 * <b>공식 작품처럼 읽힌다</b> — 프로필이 없는 UGC 와 구분되지 않는다.
	 */
	private static final String ANONYMOUS_AUTHOR = "탈퇴한 사용자";

	private static final String PUBLIC_VISIBILITY = "public";

	private static final String UNLISTED_VISIBILITY = "unlisted";

	private final JdbcClient jdbc;

	private final Clock clock;

	public WithdrawnAuthorContentHandler(@Qualifier("catalogDataSource") DataSource catalogDataSource,
			Clock clock) {
		this.jdbc = JdbcClient.create(catalogDataSource);
		this.clock = clock;
	}

	@Override
	@Transactional("catalogTransactionManager")
	public int handleWithdrawal(Collection<UUID> authorRefs) {
		if (authorRefs.isEmpty()) {
			return 0;
		}
		anonymizeAuthorNames(authorRefs);
		deleteDrafts(authorRefs);
		return unlistPublicStories(authorRefs);
	}

	private int unlistPublicStories(Collection<UUID> authorRefs) {
		return this.jdbc.sql("""
						UPDATE story SET visibility = :unlisted
						WHERE author_ref IN (:authorRefs) AND visibility = :public
						""")
				.params(Map.of("unlisted", UNLISTED_VISIBILITY, "authorRefs", authorRefs,
						"public", PUBLIC_VISIBILITY))
				.update();
	}

	/**
	 * <b>작품이 있는 작성자에게만 이름을 남긴다.</b>
	 *
	 * <p>프로필이 없던 작성자에게도 행을 만드는 것은 의도다 — 없으면 조회가 {@code null} 을
	 * 돌려주고 (I-3 — 식별자를 대신 내보내지 않는다), 화면은 <b>작성자를 아예 표시하지 않는다.</b>
	 * 내려간 작품에 필요한 것은 이름이 없는 것이 아니라 <b>떠났다는 사실</b>이다.
	 */
	private void anonymizeAuthorNames(Collection<UUID> authorRefs) {
		this.jdbc.sql("""
						INSERT INTO author_profile (player_ref, display_name, updated_at)
						SELECT DISTINCT s.author_ref, :anonymous, :now
						FROM story s
						WHERE s.author_ref IN (:authorRefs)
						ON CONFLICT (player_ref)
						DO UPDATE SET display_name = :anonymous, updated_at = :now
						""")
				.params(Map.of("anonymous", ANONYMOUS_AUTHOR, "authorRefs", authorRefs,
						"now", Instant.now(this.clock).atOffset(ZoneOffset.UTC)))
				.update();
	}

	/** 발행되지 않은 원고 (R12.4). 미리보기가 만든 사본의 파기는 §13-37 이 따로 가져갔다. */
	private void deleteDrafts(Collection<UUID> authorRefs) {
		this.jdbc.sql("DELETE FROM story_draft WHERE author_ref IN (:authorRefs)")
				.param("authorRefs", authorRefs)
				.update();
	}
}
