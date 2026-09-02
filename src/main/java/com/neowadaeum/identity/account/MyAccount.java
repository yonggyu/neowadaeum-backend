package com.neowadaeum.identity.account;

import com.neowadaeum.identity.domain.UserRole;
import com.neowadaeum.identity.domain.UserStatus;

/**
 * 내 계정의 조회 결과 (#262).
 *
 * <p><b>담기지 않은 것이 이 타입의 내용이다.</b> {@code playerRef} · 이메일 · 소셜 식별자 ·
 * 생년월일이 여기 없다 — 계약의 {@code TokenResponse} 가 이미 {@code playerRef} 를 돌려주지
 * 않기로 정했고(§13-7, I-3), 나머지는 화면이 쓰지 않는 회원 식별정보다 (R12.1).
 *
 * @param displayName 공개 표시명. 프로필을 설정하지 않았으면 {@code null}
 */
public record MyAccount(String displayName, UserRole role, UserStatus status) {
}
