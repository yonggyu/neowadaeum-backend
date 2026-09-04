package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.Turn;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 턴 영속화 (§9.2). 조회 API(B-35 History)의 커서 페이지네이션은 그 작업에서 더한다. */
public interface TurnRepository extends JpaRepository<Turn, UUID> {

	/**
	 * 직전 턴. 다음 요청의 {@code choiceId} 를 대조할 대상이다 (I-1, §4.3-2).
	 *
	 * <p>되돌려진 턴은 제외한다 — 롤백 후에는 그 이전 턴이 마지막이다 (R14.4).
	 */
	Optional<Turn> findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(UUID sessionId);

	/**
	 * 최근 턴 몇 개 — 프롬프트의 RECENT TURNS 레이어 재료다 (§5.1, R4.7, B-22).
	 *
	 * <p><b>최신이 앞이다.</b> 호출자가 뒤집어 오래된 것부터 싣는다 — 예산이 모자라면 앞에서부터
	 * 빠지는 것이 §4.4 의 축소 순서이기 때문이다.
	 *
	 * <p>되돌려진 턴은 제외한다 (R14.4). 롤백된 턴이 프롬프트에 남으면 <b>없던 일이 이야기에
	 * 계속 영향을 준다.</b>
	 */
	List<Turn> findBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(UUID sessionId, Limit limit);

	/**
	 * 미리보기 세션의 턴 전부, <b>이야기 순서로</b> (#332).
	 *
	 * <p>상한을 두지 않는 것은 미리보기가 3턴이기 때문이다 (R8.13) — 세션에 박힌 상한이
	 * 여기서 다시 필요하지 않다.
	 */
	List<Turn> findBySessionIdAndDeletedAtIsNullOrderByTurnNoAsc(UUID sessionId);

	/**
	 * 기록의 한 쪽 (§13.6, B-35).
	 *
	 * <p><b>역순이다.</b> 화면이 "위로 스크롤해 더 읽기"이므로 커서도 <b>지금 보고 있는 것보다
	 * 과거</b>를 가리킨다.
	 *
	 * <p>되돌려진 턴은 빠진다 (R14.4) — 없던 일이 이야기에 남으면 안 된다.
	 */
	List<Turn> findBySessionIdAndTurnNoLessThanAndDeletedAtIsNullOrderByTurnNoDesc(UUID sessionId,
			int turnNoExclusive, Limit limit);

	/**
	 * 요약에 병합할 구간의 턴들 (R4.5, B-34).
	 *
	 * <p><b>오래된 것이 앞이다.</b> 요약은 시간 순서를 유지해야 하며, 뒤집힌 순서로 압축하면
	 * 인과가 뒤바뀐 줄거리가 다음 턴들의 전제가 된다.
	 *
	 * <p>되돌려진 턴은 제외한다 (R14.4) — 없던 일이 요약에 남으면 <b>롤백해도 이야기에 계속
	 * 영향을 준다.</b>
	 */
	List<Turn> findBySessionIdAndDeletedAtIsNullAndTurnNoBetweenOrderByTurnNoAsc(UUID sessionId, int from, int to);

	/**
	 * 현재 챕터에서 지난 턴 수 (R7.2).
	 *
	 * <p><b>세지 않고 컬럼에 두지 않는다.</b> {@code turn.chapter_no} 로 파생 가능한 값을 저장하면
	 * 어긋날 자리가 하나 는다 (S-9 요구사항 2).
	 */
	int countBySessionIdAndChapterNoAndDeletedAtIsNull(UUID sessionId, int chapterNo);

	/** 되돌리기가 접을 대상 (R14.4, B-42). 지목한 지점보다 <b>뒤</b>의 살아 있는 턴이다. */
	List<Turn> findBySessionIdAndTurnNoGreaterThanAndDeletedAtIsNull(UUID sessionId, int turnNo);

	/** 되돌린 지점의 턴. 세션의 chapter 를 그 턴에 맞춰야 한다. */
	Optional<Turn> findBySessionIdAndTurnNoAndDeletedAtIsNull(UUID sessionId, int turnNo);

	/**
	 * 탈퇴 회원의 기록 파기 (R12.4, B-61).
	 *
	 * <p>턴 본문은 그 회원의 플레이 기록이다. 세션을 지우면서 남기면 <b>주인 없는 이야기</b>가 쌓인다.
	 *
	 * <p><b>벌크 삭제다.</b> 엔티티를 읽어 지우면 그 회원의 플레이 전체를 메모리에 올리게 된다 —
	 * 지우는 일에 필요한 것은 <b>조건</b>이지 행의 내용이 아니다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			DELETE FROM Turn t
			WHERE t.sessionId IN (SELECT s.id FROM PlaySession s WHERE s.playerRef IN :playerRefs)
			""")
	int deleteByPlayerRefs(@Param("playerRefs") Collection<UUID> playerRefs);

	/**
	 * 미리보기 작품과 함께 사라지는 기록 (§13-37, B-61).
	 *
	 * <p>작품이 지워지면 그 위의 세션은 <b>읽을 수 없는 기록</b>이 된다 — 남겨 두면
	 * 어느 작품의 것인지 물어볼 곳이 없는 행이 쌓인다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			DELETE FROM Turn t
			WHERE t.sessionId IN (SELECT s.id FROM PlaySession s WHERE s.storyId IN :storyIds)
			""")
	int deleteByStoryIds(@Param("storyIds") Collection<UUID> storyIds);
}
