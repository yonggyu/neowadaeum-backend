package com.neowadaeum.identity.domain;

/**
 * 회원 상태 (§2.2).
 *
 * <p><b>{@code WITHDRAWN} 은 삭제가 아니라 상태다.</b> 탈퇴 즉시 행을 지우면 진행 중 세션의
 * {@code player_ref} 가 가리킬 곳을 잃고, 파기 주기(R12.4)를 지켰다는 증빙도 사라진다.
 * 실제 파기·익명화는 B-61 의 배치가 수행한다.
 *
 * <p>DB 표기(소문자)로의 변환은 {@link LowerCaseEnumConverter} 한 곳에서만 한다.
 */
public enum UserStatus {

	/** 정상 회원. */
	ACTIVE,

	/** 운영 정지. 플레이·저작이 막힌다 (B-40). */
	SUSPENDED,

	/** 탈퇴 신청 완료. 파기 배치(B-61)가 처리할 때까지 이 상태로 남는다 (R12.5). */
	WITHDRAWN
}
