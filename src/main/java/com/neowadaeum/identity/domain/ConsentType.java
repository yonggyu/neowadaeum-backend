package com.neowadaeum.identity.domain;

/**
 * 동의 종류 (§2.2, R10.2).
 *
 * <p><b>{@link #AI_NOTICE} 는 "AI 고지를 읽고 <i>동의</i>함"에만 쓴다</b> (§13-8). 고지를 화면에
 * 보여 준 사실은 동의가 아니며 {@link AiNoticeImpression} 이 따로 기록한다 — 섞으면 동의 이력의
 * 법적 증빙력이 흐려진다.
 */
public enum ConsentType {

	/** 이용약관. */
	TOS,

	/** 개인정보 처리방침. */
	PRIVACY,

	/** AI 생성물 고지 동의 (R11.1). */
	AI_NOTICE,

	/** 만 15세 이상 확인 (R10.2, B-13). */
	AGE
}
