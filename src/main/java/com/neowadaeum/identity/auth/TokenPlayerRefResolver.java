package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.web.PlayerRefResolver;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 요청 주체의 {@code player_ref} — <b>인증 토큰에서 온다</b> (B-12, #34).
 *
 * <p>이 빈이 {@code DevFixedPlayerRefResolver} 를 대체한다. 그쪽은 값을 상수로 갖고 있었고
 * ADR-0004 가 그것을 <b>인증 우회</b>라고 규정했다. 이제 값의 출처는 <b>서명된 토큰</b>이며,
 * 프로파일 조건이 없다 — 조건이 필요했던 것은 우회 쪽이었다.
 *
 * <p>인증되지 않은 요청은 {@code null} 대신 {@code 401} 로 끝낸다 — <b>주인 없는 요청이 도메인에
 * 들어가지 않게</b> 한다.
 */
@Component
public class TokenPlayerRefResolver implements PlayerRefResolver {

	@Override
	public UUID currentPlayerRef() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof UUID playerRef)) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		return playerRef;
	}
}
