package com.neowadaeum.identity.api;

import com.neowadaeum.identity.account.MyAccount;
import java.util.Locale;

/**
 * 내 계정 응답 (#262).
 *
 * <p><b>{@code playerRef} 를 담지 않는다.</b> {@link TokenResponse} 가 그것을 담지 않기로 한
 * 이유가 여기서도 그대로다 — 클라이언트가 알 필요가 없고, 알 필요 없는 값을 주면 그 값이
 * 로그·에러 리포트·분석 도구로 퍼진다 (§13-7, I-3).
 *
 * <p><b>이메일·소셜 식별자·생년월일도 없다.</b> 화면이 쓰지 않는 회원 식별정보를 응답에 실어
 * 두면 <b>수집하지 않기로 한 것이 매 요청마다 네트워크를 지난다.</b>
 *
 * <p><b>{@code isLoggedIn} 이 없다.</b> 로그인 여부는 본문이 아니라 200 과 401 로 답한다 —
 * 랜딩(§13.10)이 같은 이유로 그 필드를 두지 않았다.
 *
 * <p>{@code displayName} 은 <b>키를 생략하지 않고 {@code null} 로 명시한다</b> — 프론트가 키
 * 존재 여부로 분기하지 않게 한다.
 *
 * @param displayName 공개 표시명. 설정하지 않았으면 {@code null}
 * @param role {@code user} | {@code admin}. <b>이것만으로 관리자가 되지는 않는다</b> (S-4)
 * @param status {@code active} | {@code suspended} | {@code withdrawn} (R12.5)
 */
public record MeResponse(String displayName, String role, String status) {

	static MeResponse of(MyAccount account) {
		return new MeResponse(account.displayName(), lower(account.role().name()),
				lower(account.status().name()));
	}

	/** 계약의 표기는 소문자다 — {@code sessionState} · {@code reviewStatus} 와 같은 규칙이다. */
	private static String lower(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
