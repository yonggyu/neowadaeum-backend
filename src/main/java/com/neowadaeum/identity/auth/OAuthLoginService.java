package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.OauthProvider;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 소셜 로그인 유스케이스 (B-12, §13.1).
 *
 * <p><b>외부 호출과 트랜잭션을 겹치지 않는다</b> (아키텍처 경계). ID 토큰 검증은 구글 JWKS 를
 * 부를 수 있으므로 여기서 <b>트랜잭션 밖에서</b> 끝내고, 저장은 {@link SocialAccountRegistrar} 의
 * 짧은 트랜잭션에 맡긴다.
 *
 * <p><b>순서가 이 클래스의 형태를 정한다</b> (§4.1, B-13). 기존 회원인지 먼저 보고, 최초라면
 * <b>계정을 만들기 전에</b> 가입 정보와 연령을 판정한다 — 만들고 나서 거부하면 나이를 확인받지
 * 않은 계정이 남는다 (R10.2).
 */
@Service
public class OAuthLoginService {

	private final GoogleIdTokenVerifier verifier;

	private final SocialAccountRegistrar registrar;

	private final AuthTokenService tokens;

	private final AgeGate ageGate;

	public OAuthLoginService(GoogleIdTokenVerifier verifier, SocialAccountRegistrar registrar,
			AuthTokenService tokens, AgeGate ageGate) {
		this.verifier = verifier;
		this.registrar = registrar;
		this.tokens = tokens;
		this.ageGate = ageGate;
	}

	/**
	 * 최초면 계정을 만들고, 아니면 찾는다. 어느 쪽이든 토큰 한 벌을 돌려준다.
	 *
	 * @param signup <b>최초 로그인에만 쓰인다.</b> 기존 회원이면 보지 않는다 — 로그인할 때마다
	 *     동의를 다시 받으면 동의 이력이 로그인 이력이 된다
	 * @param ipHash 동의 시점의 접속자 해시 (§12). 원문 IP 는 여기까지 오지 않는다
	 * @throws ApiException {@code UNAUTHENTICATED} 토큰 검증 실패 · {@code FORBIDDEN} 정지·탈퇴 회원 ·
	 *     {@code CONSENT_REQUIRED} 가입 정보 누락 · {@code AGE_RESTRICTED} 만 15세 미만
	 */
	public AuthTokens login(OauthProvider provider, String idToken, SignupInfo signup, String ipHash) {
		if (provider != OauthProvider.GOOGLE) {
			// MVP 는 구글 하나다 (§13-11). 값이 enum 에 있는 것과 경로가 열린 것은 다르다.
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		VerifiedSocialIdentity verified = this.verifier.verify(idToken);
		return this.tokens.issue(this.registrar.findPlayerRef(provider, verified.subject())
				.orElseGet(() -> signUp(provider, verified, signup, ipHash)));
	}

	/**
	 * 최초 로그인 = 가입 (§4.1).
	 *
	 * <p><b>판정이 전부 계정 생성보다 앞에 있다.</b> 만 15세 미만이면 {@code user} 도
	 * {@code oauth_identity} 도 만들어지지 않는다 (R10.2).
	 */
	private UUID signUp(OauthProvider provider, VerifiedSocialIdentity verified, SignupInfo signup,
			String ipHash) {
		signup.requireComplete();
		this.ageGate.requireEligible(signup.birthDate());
		return this.registrar.register(provider, verified, signup, ipHash);
	}

	/**
	 * 리프레시 회전.
	 *
	 * <p><b>리프레시 저장소는 여전히 없다</b> (§13) — 대신 <b>회원이 아직 유효한지</b>를 묻는다.
	 * 묻지 않으면 탈퇴한 회원이 토큰을 무한히 회전시킬 수 있고, 그러면 탈퇴는 <b>다음 로그인부터
	 * 적용되는 신청</b>이 된다 (R12.5, B-62).
	 *
	 * <p>조회 하나가 붙는 곳은 <b>재발급뿐</b>이다. 액세스 토큰이 짧으므로 그것으로 충분하다 —
	 * 모든 요청에 거는 것이 §13 이 피하려던 비용이었다.
	 *
	 * @throws ApiException {@code FORBIDDEN} 정지·탈퇴 회원 · {@code UNAUTHENTICATED} 파기된 매핑
	 */
	public AuthTokens refresh(String refreshToken) {
		UUID playerRef = this.tokens.resolveRefresh(refreshToken);
		this.registrar.requireActive(playerRef);
		return this.tokens.issue(playerRef);
	}
}
