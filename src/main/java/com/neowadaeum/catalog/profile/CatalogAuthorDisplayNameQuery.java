package com.neowadaeum.catalog.profile;

import com.neowadaeum.catalog.domain.AuthorProfile;
import com.neowadaeum.catalog.repository.AuthorProfileRepository;
import com.neowadaeum.common.spi.AuthorDisplayNameQuery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AuthorDisplayNameQuery} 의 catalog 쪽 구현 (#262, ADR-0002 와 같은 형태).
 *
 * <p><b>데이터를 소유한 모듈이 구현한다.</b> {@code author_profile} 은 catalog 스키마에 있고 그
 * 표를 읽을 수 있는 EMF 도 catalog 하나다 — 다른 모듈이 직접 읽으려면 스토어 분리를 깨야 한다
 * (§5.3).
 *
 * <p><b>읽기 전용 트랜잭션이며 매니저를 명시한다.</b> 후보가 넷이므로 이름 없는
 * {@code @Transactional} 은 부팅에서 실패한다.
 */
@Component
public class CatalogAuthorDisplayNameQuery implements AuthorDisplayNameQuery {

	private final AuthorProfileRepository profiles;

	public CatalogAuthorDisplayNameQuery(AuthorProfileRepository profiles) {
		this.profiles = profiles;
	}

	@Override
	@Transactional(transactionManager = "catalogTransactionManager", readOnly = true)
	public Optional<String> findDisplayName(UUID playerRef) {
		return this.profiles.findById(playerRef).map(AuthorProfile::getDisplayName);
	}
}
