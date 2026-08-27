package com.neowadaeum.catalog.config;

import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.common.spi.ServiceConfigQuery;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ServiceConfigQuery} 의 catalog 쪽 구현 (B-14, ADR-0002 와 같은 형태).
 *
 * <p><b>데이터를 소유한 모듈이 구현한다.</b> 값이 catalog 스키마에 있으므로 그것을 읽을 수 있는
 * EMF 도 catalog 하나뿐이다 — 다른 모듈이 직접 읽으려면 스토어 분리를 깨야 한다 (§5.3).
 *
 * <p><b>읽기 전용 트랜잭션이다.</b> 매니저를 명시한다 — 후보가 넷이므로 이름 없는
 * {@code @Transactional} 은 부팅에서 실패한다.
 *
 * <p>캐시를 두지 않는다. 이 값은 <b>배포 없이 갱신</b>되는 것이 존재 이유이고(R11.1), 캐시를
 * 두면 갱신 → 무효화 경로를 함께 설계해야 한다 (ADR-0002 가 블록리스트에서 지적한 것과 같다).
 * 호출 빈도가 문제가 되는 시점은 B-37(랜딩)이며 그때 근거를 갖고 정한다.
 */
@Component
public class CatalogServiceConfigQuery implements ServiceConfigQuery {

	private final ServiceConfigRepository configs;

	public CatalogServiceConfigQuery(ServiceConfigRepository configs) {
		this.configs = configs;
	}

	@Override
	@Transactional(transactionManager = "catalogTransactionManager", readOnly = true)
	public Optional<String> find(String key) {
		return this.configs.findById(key).map(ServiceConfig::getConfigValue);
	}
}
