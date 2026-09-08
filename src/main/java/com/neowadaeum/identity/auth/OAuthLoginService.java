package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.OauthProvider;
import java.util.Optional;
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
 *
 * <p><b>nonce 의 발급과 소비가 둘 다 여기 있다</b> (§13-87). 대조가 저장소를 봐야 성립하므로
 * 토큰 검증기의 일이 아니고, 저장소 접근이므로 컨트롤러의 일도 아니다 — <b>유스케이스가 자기
 * 상태의 양 끝을 갖는다.</b>
 *
 * <p><b>소비는 거절 판정을 전부 지난 뒤다</b> (§13-88, 이슈 #429). 그보다 앞에 두면 <b>계정도
 * 토큰도 만들어지지 않은 거절이 nonce 를 태운다</b> — {@code CONSENT_REQUIRED} 를 받은 화면이
 * 동의를 채워 같은 토큰으로 다시 보내면 {@code LOGIN_NONCE_INVALID} 를 만나고, <b>최초 가입이
 * 어떤 순서로도 성립하지 않는다.</b> nonce 는 새 ID 토큰 안에만 들어갈 수 있으므로 화면이
 * 우회할 수도 없다.
 *
 * <p><b>옮긴 것은 시점뿐이고 원자성은 그대로다</b> (§13-87). 소비는 여전히 <b>단 한 번의 원자적
 * 삭제</b>이며 <b>계정 생성과 토큰 발급보다 앞</b>이다 — 같은 nonce 를 든 두 요청 중 토큰을 받는
 * 쪽은 하나뿐이라는 성질이 이 재배치로 흔들리지 않는다. 대가는 <b>소비 앞에 회원 조회 하나가
 * 붙는 것</b>이며, 그 자리에 닿으려면 이미 서명 검증을 통과한 ID 토큰이 있어야 하고 컨트롤러의
 * IP 기준 한도가 그보다 앞에 걸려 있다 (S-8).
 */
@Service
public class OAuthLoginService {

	private final GoogleIdTokenVerifier verifier;

	private final SocialAccountRegistrar registrar;

	private final AuthTokenService tokens;

	private final AgeGate ageGate;

	private final LoginNonceStore nonces;

	public OAuthLoginService(GoogleIdTokenVerifier verifier, SocialAccountRegistrar registrar,
			AuthTokenService tokens, AgeGate ageGate, LoginNonceStore nonces) {
		this.verifier = verifier;
		this.registrar = registrar;
		this.tokens = tokens;
		this.ageGate = ageGate;
		this.nonces = nonces;
	}

	/**
	 * 로그인 앞에 한 번 부른다 (§13-87).
	 *
	 * <p>클라이언트는 이 값을 GIS 의 {@code initialize({ nonce })} 에 실어야 하고, 그러면 그 값이
	 * ID 토큰의 클레임으로 돌아온다. <b>이 왕복 하나가 이 결정의 대가다</b> — 그 대신 토큰만
	 * 가로챈 쪽은 통과하지 못한다.
	 *
	 * <p><b>인증을 요구하지 않는다.</b> 로그인 앞이므로 요구할 자격 증명이 없다. 대신 컨트롤러가
	 * IP 기준 호출 한도를 건다 (S-8) — 인증 없이 열리고 <b>서버에 상태를 만드는</b> 경로이기
	 * 때문이다.
	 */
	public LoginNonce issueNonce() {
		return this.nonces.issue();
	}

	/**
	 * 최초면 계정을 만들고, 아니면 찾는다. 어느 쪽이든 토큰 한 벌을 돌려준다.
	 *
	 * @param signup <b>최초 로그인에만 쓰인다.</b> 기존 회원이면 보지 않는다 — 로그인할 때마다
	 *     동의를 다시 받으면 동의 이력이 로그인 이력이 된다
	 * @param ipHash 동의 시점의 접속자 해시 (§12). 원문 IP 는 여기까지 오지 않는다
	 * @throws ApiException {@code UNAUTHENTICATED} 토큰 검증 실패 ·
	 *     {@code LOGIN_NONCE_INVALID} 서버가 발급한 nonce 가 토큰에 없거나 이미 쓰였다 ·
	 *     {@code FORBIDDEN} 정지·탈퇴 회원 · {@code CONSENT_REQUIRED} 가입 정보 누락 ·
	 *     {@code AGE_RESTRICTED} 만 15세 미만
	 */
	public AuthTokens login(OauthProvider provider, String idToken, SignupInfo signup, String ipHash) {
		if (provider != OauthProvider.GOOGLE) {
			// MVP 는 구글 하나다 (§13-11). 값이 enum 에 있는 것과 경로가 열린 것은 다르다.
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		VerifiedSocialIdentity verified = this.verifier.verify(idToken);
		Optional<UUID> member = this.registrar.findPlayerRef(provider, verified.subject());
		if (member.isEmpty()) {
			// 최초 로그인이면 가입 판정이 먼저다 — 거절이 nonce 를 태우면 2차 요청이 통과할 길이 없다 (§13-88).
			requireEligibleToSignUp(signup);
		}
		requireIssuedNonce(verified.nonce());
		return this.tokens.issue(member.orElseGet(
				() -> this.registrar.register(provider, verified, signup, ipHash)));
	}

	/**
	 * <b>이 토큰이 이 로그인 시도를 위해 발급됐는가</b> (§13-87).
	 *
	 * <p>서명·발급자·대상·만료는 <b>토큰이 유효한가</b>까지만 말한다. 그것을 통과한 토큰을
	 * 가로채 다른 자리에서 다시 쓰는 면이 남고, 그 면을 닫는 것이 이 한 줄이다.
	 *
	 * <p><b>실패를 구분해 알리지 않는다</b> (S-6). 없는 것 · 안 맞는 것 · 만료된 것 · 이미
	 * 쓰인 것이 전부 같은 코드다 — 어느 쪽인지 알려 주면 그것이 서버 상태를 묻는 창구가 된다.
	 *
	 * <p><b>그래서 로그인은 재시도 가능한 요청이 아니다.</b> 같은 ID 토큰을 다시 보내면 두 번째는
	 * 막힌다 — 1회성이 뜻하는 바가 그것이고, 다시 하려면 nonce 부터 다시 받아야 한다.
	 */
	private void requireIssuedNonce(String nonce) {
		if (!this.nonces.consume(nonce)) {
			throw new ApiException(ErrorCode.LOGIN_NONCE_INVALID);
		}
	}

	/**
	 * 최초 로그인 = 가입, 그 자격을 먼저 묻는다 (§4.1).
	 *
	 * <p><b>판정이 전부 계정 생성보다 앞에 있다.</b> 만 15세 미만이면 {@code user} 도
	 * {@code oauth_identity} 도 만들어지지 않는다 (R10.2).
	 *
	 * <p><b>둘 다 요청 본문만 보고 끝난다</b> — 저장소도 외부 호출도 필요 없다. 그래서 이
	 * 판정을 nonce 소비 앞에 두는 데 드는 비용이 없고, 거절당한 요청은 <b>서버에 아무 흔적도
	 * 남기지 않은 채</b> 끝난다 (§13-88, 이슈 #429).
	 *
	 * <p><b>기존 회원에게는 부르지 않는다.</b> 그쪽은 이 값을 보내지 않으므로, 회원 조회로
	 * 가르기 전에 부르면 <b>정상 로그인이 전부 {@code CONSENT_REQUIRED} 가 된다.</b>
	 */
	private void requireEligibleToSignUp(SignupInfo signup) {
		signup.requireComplete();
		this.ageGate.requireEligible(signup.birthDate());
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
