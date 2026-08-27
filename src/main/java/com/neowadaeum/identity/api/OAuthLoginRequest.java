package com.neowadaeum.identity.api;

import com.neowadaeum.identity.auth.SignupInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

/**
 * 소셜 로그인 요청 (§13-22 의 {@code OAuthLoginRequest}).
 *
 * <p><b>{@code birthDate} 와 {@code consents} 는 최초 로그인(가입)에만 필요하다.</b> 기존 회원은
 * {@code idToken} 만 보낸다 — 로그인할 때마다 동의를 다시 받으면 동의 이력이 로그인 이력이 된다.
 *
 * <p>둘의 <b>존재</b>는 여기서 강제하지 않는다. 가입인지 아닌지는 ID 토큰을 검증하고 계정을 찾아
 * 봐야 알 수 있고, 그 판단은 서비스의 몫이다 — 여기서 {@code @NotNull} 을 걸면 <b>기존 회원의
 * 로그인이 막힌다.</b> 누락은 가입 경로에서 {@code 400 CONSENT_REQUIRED} 로 나간다.
 *
 * @param idToken   provider 가 발급한 ID 토큰. <b>서버가 검증한다</b>
 * @param birthDate 만 나이 계산의 원본. <b>KST 로 판정한다</b> (§13-24)
 * @param consents  화면에서 받은 동의. 필수 3종은 §4.1 이 정한다
 */
public record OAuthLoginRequest(@NotBlank String idToken, LocalDate birthDate,
		@Valid List<ConsentItem> consents) {

	SignupInfo toSignupInfo() {
		return new SignupInfo(this.birthDate,
				(this.consents == null) ? List.of() : this.consents.stream().map(ConsentItem::toDecision).toList());
	}
}
