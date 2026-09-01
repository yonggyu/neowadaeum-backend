package com.neowadaeum.identity.repository;

import com.neowadaeum.identity.domain.OauthIdentity;
import com.neowadaeum.identity.domain.OauthProvider;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 소셜 계정 연결 영속화 (§2.2).
 *
 * <p>B-12 의 로그인은 {@code (provider, subject)} 로 기존 회원을 찾고 없으면 새로 만든다. 그 조회가
 * 아래 하나이며, DB 의 UNIQUE 제약과 같은 축이라 결과는 언제나 0 또는 1건이다.
 */
public interface OauthIdentityRepository extends JpaRepository<OauthIdentity, UUID> {

	Optional<OauthIdentity> findByProviderAndSubject(OauthProvider provider, String subject);

	/**
	 * 탈퇴 파기 (R12.5, B-61).
	 *
	 * <p><b>이 행이 남아 있으면 파기가 아니다.</b> 소셜 계정 식별자와 이메일 해시가 곧 그 사람을
	 * 다시 찾는 길이며, {@code player_ref} 만 끊고 이것을 남기면 <b>같은 계정으로 로그인했을 때
	 * 지운 회원으로 돌아간다.</b>
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("DELETE FROM OauthIdentity o WHERE o.userId IN :userIds")
	int deleteByUserIds(@Param("userIds") Collection<UUID> userIds);
}
