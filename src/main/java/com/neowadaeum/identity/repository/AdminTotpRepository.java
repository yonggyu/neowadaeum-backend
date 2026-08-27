package com.neowadaeum.identity.repository;

import com.neowadaeum.identity.domain.AdminTotp;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 관리자 TOTP 등록 정보. 회원당 한 벌이므로 조회는 기본키 하나로 끝난다. */
public interface AdminTotpRepository extends JpaRepository<AdminTotp, UUID> {
}
