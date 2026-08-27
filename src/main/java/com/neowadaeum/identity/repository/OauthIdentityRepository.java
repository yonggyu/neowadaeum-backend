package com.neowadaeum.identity.repository;

import com.neowadaeum.identity.domain.OauthIdentity;
import com.neowadaeum.identity.domain.OauthProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 소셜 계정 연결 영속화 (§2.2).
 *
 * <p>B-12 의 로그인은 {@code (provider, subject)} 로 기존 회원을 찾고 없으면 새로 만든다. 그 조회가
 * 아래 하나이며, DB 의 UNIQUE 제약과 같은 축이라 결과는 언제나 0 또는 1건이다.
 */
public interface OauthIdentityRepository extends JpaRepository<OauthIdentity, UUID> {

	Optional<OauthIdentity> findByProviderAndSubject(OauthProvider provider, String subject);
}
