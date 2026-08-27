package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
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
	 * 이 작품의 내 진행 중 세션 (§13.3).
	 *
	 * <p>작품당 {@code active} 는 1개지만(§13-9) 목록으로 받는다 — {@code Optional} 로 받으면
	 * 제약이 깨진 날 조회가 예외로 터지고, 그 예외는 화면 전체를 죽인다. 여기서는 <b>가장 최근
	 * 하나</b>를 보여 주는 것으로 충분하다.
	 */
	List<PlaySession> findByPlayerRefAndStoryIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(
			UUID playerRef, UUID storyId, SessionStatus status, Limit limit);
}
