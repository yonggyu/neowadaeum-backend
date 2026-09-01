package com.neowadaeum.catalog.repository;

import com.neowadaeum.catalog.domain.ServiceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 운영 설정 영속화 (R11.1).
 *
 * <p>키가 PK 이므로 조회는 {@code findById} 하나다 — 별도 메서드를 두지 않는다.
 */
public interface ServiceConfigRepository extends JpaRepository<ServiceConfig, String> {
}
