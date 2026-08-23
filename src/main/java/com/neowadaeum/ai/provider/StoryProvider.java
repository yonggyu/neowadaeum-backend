package com.neowadaeum.ai.provider;

/**
 * 한 턴의 본문과 선택지를 생성하는 seam (S-3).
 *
 * <p><b>순수 DTO 만 주고받는다.</b> {@code ai} 모듈은 도메인 엔티티를 알지 못하며, 이것이 I-3 의
 * 구조적 보장이다 — 엔티티를 모르면 회원 식별정보를 실을 방법 자체가 없다.
 *
 * <p><b>범위 — 이것은 B-18 이 아니다.</b> B-18 의 "4메서드 + capabilities" 와 {@code AiGateway} 는
 * 이 레포에 없는 문서에 정의되어 있어 형태를 추측하지 않는다. 여기서는 S-3 가 실제로 쓰는 최소
 * 형태만 정의하고, B-18 복귀 시점에 확장한다 (이슈 #45 의 범위 경계 참조).
 *
 * <p>구현체 선택 권한은 관리자 전용이며 사용자에게 노출하지 않는다 (I-14). 세션은 생성 시
 * provider 에 고정된다 (I-4).
 */
public interface StoryProvider {

	/** 설정·로그에서 이 구현을 지목하는 안정적인 식별자. */
	String providerId();

	/**
	 * 다음 턴을 생성한다.
	 *
	 * <p>반환값은 <b>제안</b>이다. 상태 변화·챕터 전환·엔딩은 서버가 최종 결정한다 (I-9, I-10, I-11).
	 */
	TurnResult generateTurn(TurnRequest request);
}
