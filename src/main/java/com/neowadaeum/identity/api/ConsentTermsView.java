package com.neowadaeum.identity.api;

import java.util.List;

/**
 * 가입 화면이 읽는 약관 메타 (이슈 #261).
 *
 * <p><b>회원 정보가 하나도 없다.</b> 이 경로는 가입 <b>전에</b> 불리므로 인증 없이 열리며
 * (계약의 {@code security: []}), 인증 없이 열리는 응답에 회원에 관한 값이 섞이면 그것이 그대로
 * 유출 경로가 된다 (S-9).
 *
 * @param terms 동의 종류별 현재 약관. 순서는 {@code tos → privacy → ai_notice → age} 다
 */
public record ConsentTermsView(List<Term> terms) {

	/**
	 * 약관 한 건.
	 *
	 * @param consentType {@code tos} · {@code privacy} · {@code ai_notice} · {@code age}
	 * @param version     <b>가입 요청에 그대로 되돌려 보내는 값</b>이다. 프론트가 판본을 알 필요가
	 *                    없게 하는 것이 이 경로의 존재 이유다 (이슈 #261)
	 * @param documentUrl 약관 본문 주소. <b>{@code null} 일 수 있다</b> — 키를 생략하지 않는다
	 * @param required    가입에 <b>사용자의 동의가 필요한가.</b> {@code age} 만 {@code false} 이며,
	 *                    그것은 체크박스가 아니라 <b>서버가 생년월일로 판정해 스스로 기록하는
	 *                    사실</b>이기 때문이다 (R10.2, §13-24)
	 */
	public record Term(String consentType, String version, String documentUrl, boolean required) {
	}
}
