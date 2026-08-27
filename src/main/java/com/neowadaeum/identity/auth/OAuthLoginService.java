package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.OauthProvider;
import org.springframework.stereotype.Service;

/**
 * 소셜 로그인 유스케이스 (B-12, §13.1).
 *
 * <p><b>외부 호출과 트랜잭션을 겹치지 않는다</b> (아키텍처 경계). ID 토큰 검증은 구글 JWKS 를
 * 부를 수 있으므로 여기서 <b>트랜잭션 밖에서</b> 끝내고, 저장은 {@link SocialAccountRegistrar} 의
 * 짧은 트랜잭션에 맡긴다.
 *
 * <p><b>연령 게이트와 동의 기록은 여기에 없다.</b> B-13 의 범위다 — 이 작업은 로그인이 서는 것까지다.
 */
@Service
public class OAuthLoginService {

	private final GoogleIdTokenVerifier verifier;

	private final SocialAccountRegistrar registrar;

	private final AuthTokenService tokens;

	public OAuthLoginService(GoogleIdTokenVerifier verifier, SocialAccountRegistrar registrar,
			AuthTokenService tokens) {
		this.verifier = verifier;
		this.registrar = registrar;
		this.tokens = tokens;
	}

	/**
	 * 최초면 계정을 만들고, 아니면 찾는다. 어느 쪽이든 토큰 한 벌을 돌려준다.
	 *
	 * @throws ApiException {@code UNAUTHENTICATED} — 토큰 검증 실패. {@code FORBIDDEN} — 정지·탈퇴 회원
	 */
	public AuthTokens login(OauthProvider provider, String idToken) {
		if (provider != OauthProvider.GOOGLE) {
			// MVP 는 구글 하나다 (§13-11). 값이 enum 에 있는 것과 경로가 열린 것은 다르다.
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return this.tokens.issue(this.registrar.linkOrCreate(provider, this.verifier.verify(idToken)));
	}

	/** 리프레시 회전. 저장소를 건드리지 않는다 — 상태 없는 리프레시의 실질이다. */
	public AuthTokens refresh(String refreshToken) {
		return this.tokens.issue(this.tokens.resolveRefresh(refreshToken));
	}
}
