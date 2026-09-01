package com.neowadaeum.identity.repository;

import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.domain.UserStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 회원 영속화 (§2.2).
 *
 * <p>이 리포지터리는 {@code identityEntityManagerFactory} 에만 묶인다. 스캔 범위가
 * {@code com.neowadaeum.identity} 로 고정되어 있어 다른 스토어의 엔티티를 볼 수 없다 —
 * 크로스 스키마 조인이 <b>쓰지 않기로 한 것</b>이 아니라 <b>쓸 수 없는 것</b>이 된다 (§5.3).
 */
public interface UserRepository extends JpaRepository<User, UUID> {

	/** 다른 스토어에서 돌아오는 유일한 경로다 — {@code user.id} 는 밖으로 나가지 않는다 (I-3). */
	Optional<User> findByPlayerRef(UUID playerRef);

	/**
	 * 파기를 기다리는 탈퇴 회원의 {@code playerRef} 들 (R12.5, B-61).
	 *
	 * <p><b>{@code playerRef} 만 고른다.</b> 이 값을 받는 쪽은 identity 밖이고 {@code user.id} 는
	 * 그 경계를 넘지 않는다 (I-3).
	 */
	@Query("SELECT u.playerRef FROM User u WHERE u.status = :withdrawn AND u.purgedAt IS NULL")
	List<UUID> findPlayerRefsPendingPurge(@Param("withdrawn") UserStatus withdrawn);

	/** 파기 대상의 회원 식별자. {@code identity} 안에서만 쓴다 — 자기 표를 지우기 위해서다. */
	@Query("SELECT u.id FROM User u WHERE u.playerRef IN :playerRefs AND u.status = :withdrawn "
			+ "AND u.purgedAt IS NULL")
	List<UUID> findIdsPendingPurge(@Param("playerRefs") Collection<UUID> playerRefs,
			@Param("withdrawn") UserStatus withdrawn);

	/**
	 * 매핑을 끊는다 (R12.5).
	 *
	 * <p><b>벌크 UPDATE 인 이유</b> — {@code player_ref} 는 엔티티에서 {@code updatable = false}
	 * 다. 평상시 경로에는 바꿀 방법이 없어야 하고, 파기는 그 경로가 아니다.
	 *
	 * <p><b>생년월일도 함께 비운다.</b> R12.1 이 식별정보로 지목한 값이며, 탈퇴한 회원에 대해
	 * 그것을 들고 있을 목적이 남아 있지 않다. 연령 동의 사실은 {@code consent_log} 에 있다.
	 *
	 * <p><b>이미 파기된 회원은 다시 세지 않는다</b> — 조건에 {@code purgedAt IS NULL} 이 있어
	 * 반복 실행이 건수를 부풀리지 않는다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE User u SET u.playerRef = NULL, u.birthDate = NULL, u.purgedAt = :now "
			+ "WHERE u.playerRef IN :playerRefs AND u.status = :withdrawn AND u.purgedAt IS NULL")
	int purgePlayerRefs(@Param("playerRefs") Collection<UUID> playerRefs,
			@Param("withdrawn") UserStatus withdrawn, @Param("now") Instant now);
}
