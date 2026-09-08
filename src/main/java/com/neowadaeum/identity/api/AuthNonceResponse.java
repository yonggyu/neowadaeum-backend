package com.neowadaeum.identity.api;

import com.neowadaeum.identity.auth.LoginNonce;

/**
 * 로그인 nonce 발급 응답 (§13-87, 이슈 #424).
 *
 * <p>클라이언트는 이 값을 GIS 의 {@code initialize({ nonce })} 에 그대로 싣는다. 그러면 그 값이
 * ID 토큰의 클레임으로 돌아오고, 서버가 <b>자기가 발급한 것과 대조</b>한다.
 *
 * <p><b>회원에 관한 값이 하나도 없다.</b> 로그인 <b>전</b>이라 있을 수가 없다 (I-3, S-9).
 *
 * @param nonce            토큰에 실어 돌려보낼 값
 * @param expiresInSeconds 유효한 남은 초. 넘긴 뒤의 로그인은 {@code 401 LOGIN_NONCE_INVALID} 다
 */
public record AuthNonceResponse(String nonce, long expiresInSeconds) {

	static AuthNonceResponse of(LoginNonce issued) {
		return new AuthNonceResponse(issued.value(), issued.expiresInSeconds());
	}
}
