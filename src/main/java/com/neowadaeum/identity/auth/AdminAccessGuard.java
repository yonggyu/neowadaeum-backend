package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AdminAuditRecorder;
import com.neowadaeum.common.support.Sha256;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.domain.UserRole;
import com.neowadaeum.identity.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 관리자 경로의 문 (S-4, R14.6).
 *
 * <p><b>세 조건은 AND 다.</b> 역할이 맞아도 허용목록 밖이면 막히고, 그 반대도 같다 — 하나가
 * 통과했다고 나머지를 건너뛰지 않는다. 2FA 는 B-40(2/2) 가 이 자리에 더한다.
 *
 * <p><b>막힌 요청도 감사에 남는다</b> (R14.5). 허용목록 밖에서 온 요청이야말로 남아야 할
 * 기록이며, 통과한 것만 남기면 <b>감사 로그가 "정상 운영 일지"가 된다.</b>
 *
 * <p><b>거절 이유를 응답으로 알리지 않는다</b> (S-6). 역할이 없어서인지 IP 때문인지 알려주면
 * 어느 쪽을 맞춰야 하는지에 대한 단서가 된다 — 둘 다 {@code 403 FORBIDDEN} 이다.
 */
@Component
public class AdminAccessGuard {

	/** 감사에 남는 이름. 거절도 행위다. */
	static final String DENIED_ACTION = "admin.access.denied";

	private final UserRepository users;

	private final AdminAccessProperties access;

	private final AdminAuditRecorder audit;

	public AdminAccessGuard(UserRepository users, AdminAccessProperties access, AdminAuditRecorder audit) {
		this.users = users;
		this.access = access;
		this.audit = audit;
	}

	/**
	 * 관리자로 통과시킨다.
	 *
	 * @return 행위자의 {@code user.id}. <b>감사 기록이 사람을 가리켜야 하므로</b> playerRef 가 아니다
	 * @throws ApiException {@code FORBIDDEN} — 어느 조건이든 하나라도 어긋나면
	 */
	public UUID requireAdmin(UUID playerRef, HttpServletRequest request) {
		String ipHash = Sha256.hex(request.getRemoteAddr());
		Optional<User> user = this.users.findByPlayerRef(playerRef);
		UUID adminUserId = user.map(User::getId).orElse(null);

		boolean roleOk = user.map(found -> found.getRole() == UserRole.ADMIN).orElse(false);
		boolean ipOk = this.access.allows(request.getRemoteAddr());
		if (!roleOk || !ipOk) {
			deny(adminUserId, roleOk, ipOk, ipHash);
		}
		return adminUserId;
	}

	/**
	 * 거절을 남긴다.
	 *
	 * <p>회원을 특정하지 못한 경우에도 남겨야 하지만 {@code admin_user_id} 가 NOT NULL 이다 —
	 * 그때는 <b>남기지 못한다는 사실 자체가 문제</b>이므로 애플리케이션 로그에만 남는다.
	 * 알려진 회원의 시도는 감사에 들어간다.
	 *
	 * <p>{@code payload} 에 <b>어느 조건이 어긋났는지</b>는 담는다 — 응답과 달리 감사는 운영자가
	 * 보는 것이고, 그 정보 없이는 사고 조사가 되지 않는다.
	 */
	private void deny(UUID adminUserId, boolean roleOk, boolean ipOk, String ipHash) {
		if (adminUserId != null) {
			this.audit.record(adminUserId, DENIED_ACTION, "admin", null,
					Map.of("roleOk", roleOk, "ipOk", ipOk), ipHash);
		}
		throw new ApiException(ErrorCode.FORBIDDEN);
	}

	/** 통과한 행위를 남긴다 (R14.5). 관리자 기능은 <b>전건</b>이 기록 대상이다. */
	public void recordAction(UUID adminUserId, String action, String targetType, UUID targetId,
			Map<String, Object> payload, HttpServletRequest request) {
		this.audit.record(adminUserId, action, targetType, targetId, payload,
				Sha256.hex(request.getRemoteAddr()));
	}
}
