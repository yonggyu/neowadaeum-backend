package com.neowadaeum.identity.repository;

import com.neowadaeum.identity.domain.AdminTotp;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 관리자 TOTP 등록 정보. 회원당 한 벌이므로 조회는 기본키 하나로 끝난다. */
public interface AdminTotpRepository extends JpaRepository<AdminTotp, UUID> {

	/**
	 * 탈퇴 파기 (R12.5, B-61).
	 *
	 * <p><b>이것은 자격 증명이다.</b> 회원이 사라진 뒤에도 남는 2FA 시크릿은 <b>아무도 소유하지
	 * 않은 열쇠</b>이며, 파기 대상에서 빠뜨릴 이유가 없다 (S-4).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("DELETE FROM AdminTotp t WHERE t.userId IN :userIds")
	int deleteByUserIds(@Param("userIds") Collection<UUID> userIds);
}
