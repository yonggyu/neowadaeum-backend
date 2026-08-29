package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 세션 영속화 (§9.2 — Repository 는 영속화만 한다).
 *
 * <p>이 리포지터리는 {@code playEntityManagerFactory} 에만 묶인다. 스캔 범위가
 * {@code com.neowadaeum.play} 로 고정되어 있어(config) 다른 스토어의 엔티티를 볼 수 없다 — 크로스 스키마
 * 조인이 <b>쓰지 않기로 한 것</b>이 아니라 <b>쓸 수 없는 것</b>이 되는 지점이다 (§5.3).
 */
public interface PlaySessionRepository extends JpaRepository<PlaySession, UUID> {

	/**
	 * 작품당 {@code active} 세션 1개 (§13-9).
	 *
	 * <p>애플리케이션이 먼저 확인하더라도 동시 요청 두 개는 그 확인을 나란히 통과한다 —
	 * 마지막 방어선은 DB 의 partial unique index 다. 이 조회는 <b>사용자에게 409 를 돌려주기
	 * 위한 것</b>이지 유일성을 보장하는 수단이 아니다.
	 */
	boolean existsByPlayerRefAndStoryIdAndStatus(UUID playerRef, UUID storyId, SessionStatus status);

	/**
	 * 이어하기 목록 (§13.2).
	 *
	 * <p><b>{@code playerRef} 로만 찾는다</b> (I-3). 이 스토어는 회원을 특정할 값을 모르며,
	 * 그래서 남의 세션이 섞일 경로도 없다.
	 *
	 * <p>최근에 이어가던 것이 위로 온다. 개수를 제한하는 것은 화면이 그만큼만 보여 주기
	 * 때문이며, 전체 목록은 B-36 의 몫이다.
	 */
	List<PlaySession> findByPlayerRefAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID playerRef,
			SessionStatus status, Limit limit);

	/**
	 * 작품별 플레이 횟수 (§13.7, R13.4).
	 *
	 * <p>지운 세션은 세지 않는다 — 사용자가 없앤 것을 작성자의 지표로 삼지 않는다.
	 *
	 * <p><b>도달률(B-39)과 다른 값이다.</b> 그쪽은 배치가 갱신하는 집계이고 이것은 목록 한 줄을
	 * 그리기 위한 즉석 계산이다. 작품 수가 적은 화면이므로 지금은 이것으로 충분하다.
	 */
	long countByStoryIdAndDeletedAtIsNull(UUID storyId);

	/**
	 * 내 세션 목록의 한 쪽 (§13.7, B-36).
	 *
	 * <p><b>{@code active} 와 {@code completed} 만 부른다</b> (§13-6). 버려지거나 만료된 세션은
	 * 목록에 없다 — 사용자가 이어갈 수도 되돌아볼 수도 없는 것을 보여 줄 이유가 없다.
	 *
	 * <p>커서는 {@code updatedAt} 이다. 같은 시각이 둘일 수 있으므로 {@code id} 를 함께 본다 —
	 * 그러지 않으면 쪽 경계에서 겹치거나 사라진다.
	 *
	 * <p><b>커서 유무로 조회를 나눈다.</b> 한 쿼리에 {@code :cursorAt IS NULL} 을 넣으면
	 * PostgreSQL 이 <b>null 파라미터의 타입을 정하지 못해</b> 첫 쪽 요청이 전부 실패한다 —
	 * CI 가 그것을 500 으로 잡았다.
	 */
	List<PlaySession> findByPlayerRefAndStatusAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(UUID playerRef,
			SessionStatus status, Limit limit);

	/** 커서 이후 (§13.7). {@code (updatedAt, id)} 키셋이며 파라미터에 {@code null} 이 없다. */
	@Query("""
			SELECT s FROM PlaySession s
			WHERE s.playerRef = :playerRef AND s.status = :status AND s.deletedAt IS NULL
			  AND (s.updatedAt < :cursorAt OR (s.updatedAt = :cursorAt AND s.id < :cursorId))
			ORDER BY s.updatedAt DESC, s.id DESC
			""")
	List<PlaySession> findMineAfter(@Param("playerRef") UUID playerRef, @Param("status") SessionStatus status,
			@Param("cursorAt") Instant cursorAt, @Param("cursorId") UUID cursorId, Limit limit);

	/**
	 * 이 작품의 내 진행 중 세션 (§13.3).
	 *
	 * <p>작품당 {@code active} 는 1개지만(§13-9) 목록으로 받는다 — {@code Optional} 로 받으면
	 * 제약이 깨진 날 조회가 예외로 터지고, 그 예외는 화면 전체를 죽인다. 여기서는 <b>가장 최근
	 * 하나</b>를 보여 주는 것으로 충분하다.
	 */
	List<PlaySession> findByPlayerRefAndStoryIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(
			UUID playerRef, UUID storyId, SessionStatus status, Limit limit);

	/**
	 * 무활동 세션을 만료로 바꾼다 (§4.7, B-61).
	 *
	 * <p><b>지우지 않는다.</b> 기록은 남고 이어갈 수만 없게 된다 — 지나간 플레이는 계속 읽힌다.
	 *
	 * <p><b>{@code updated_at} 을 건드리지 않는다.</b> 만료 처리를 활동으로 기록하면 그 세션은
	 * <b>방금 손댄 것</b>이 되고, 다음 회차의 판정 근거가 이번 회차 때문에 흔들린다.
	 *
	 * <p>상태를 <b>파라미터로</b> 넘긴다 — 컨버터가 저장 표기를 정하므로 (소문자다) JPQL 에
	 * 리터럴을 적으면 그 규칙이 두 곳에 생긴다.
	 */
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query("""
			UPDATE PlaySession s SET s.status = :expired, s.expiresAt = :now
			WHERE s.status = :active AND s.deletedAt IS NULL AND s.updatedAt < :idleBefore
			""")
	int expireIdle(com.neowadaeum.play.domain.SessionStatus expired,
			com.neowadaeum.play.domain.SessionStatus active, java.time.Instant idleBefore,
			java.time.Instant now);

	/**
	 * 탈퇴 회원의 세션 파기 (R12.4, B-61).
	 *
	 * <p><b>만료와 다르다.</b> 만료는 이어갈 수만 없게 만들고 기록을 남긴다 — 사용자가 자기가
	 * 어디까지 갔었는지를 잃지 않기 위해서다. 탈퇴에는 그 사용자가 없다.
	 *
	 * <p><b>{@code deleted_at} 을 보지 않는다.</b> 사용자가 지운 세션도 파기 대상이다 — 그것은
	 * 화면에서 치운 것이지 지워진 것이 아니었다.
	 *
	 * <p>세션에 매달린 것들을 먼저 지운 뒤에 부른다 (FK).
	 */
	@org.springframework.data.jpa.repository.Modifying(clearAutomatically = true,
			flushAutomatically = true)
	@org.springframework.data.jpa.repository.Query(
			"DELETE FROM PlaySession s WHERE s.playerRef IN :playerRefs")
	int deleteByPlayerRefs(
			@org.springframework.data.repository.query.Param("playerRefs")
			java.util.Collection<UUID> playerRefs);
}
