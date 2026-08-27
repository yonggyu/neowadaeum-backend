package com.neowadaeum.identity.domain;

/**
 * 회원 역할 (R14.6, S-4).
 *
 * <p><b>역할만으로는 관리자가 되지 못한다.</b> S-4 는 역할 · IP 허용목록 · 2FA 를 <b>모두</b>
 * 요구한다 — 셋은 AND 이며, 하나가 통과했다고 나머지를 건너뛰지 않는다.
 *
 * <p>DB 표기(소문자)로의 변환은 {@code LowerCaseEnumConverter} 한 곳에서만 한다.
 */
public enum UserRole {

	/** 기본값. 가입으로 얻는 역할이다. */
	USER,

	/** 관리자. <b>승격은 운영에서 한다</b> — 코드에도 마이그레이션에도 만드는 경로가 없다 (S-11). */
	ADMIN
}
