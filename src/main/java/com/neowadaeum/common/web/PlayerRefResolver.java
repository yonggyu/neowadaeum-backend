package com.neowadaeum.common.web;

import java.util.Optional;
import java.util.UUID;

/**
 * 현재 요청의 {@code player_ref} 를 판별한다.
 *
 * <p><b>비-Identity 스토어는 {@code user.id} 를 저장하지 않는다</b> (I-3, §5.3). 회원과 무관한
 * UUID 하나만 도메인에 흘려보내며, 그것이 이 인터페이스가 존재하는 이유다.
 *
 * <p><b>구현 빈이 없으면 부팅에 실패한다.</b> 기본 구현을 두지 않는 것이 요점이다 —
 * {@code @ConditionalOnMissingBean} 으로 "아무나 통과" 구현이 끼어들면 인증이 없는 서버가 조용히
 * 뜬다. 세이프티 SPI(ADR-0002)와 같은 원칙이다.
 *
 * <p><b>구현은 하나뿐이며 프로파일 조건이 없다</b> — 인증 토큰에서 판별한다 (B-12).
 * 슬라이스 기간에 있던 {@code dev} 고정 값 구현은 <b>인증 우회</b>였고 B-12 가 제거했다 (#34).
 * 그 제거가 되돌려지지 않는다는 것은 {@code AuthBypassRemovalTests} 가 확인한다.
 *
 * <p><b>주체가 없을 수도 있는 경로가 생겼다</b> (§13-54, 이슈 #306). 라이브러리와 작품 상세는
 * 인증 밖으로 열렸고, 그 두 화면은 <b>익명이면 개인 필드를 비운 채</b> 나간다. 그래서 판별이
 * 두 갈래다 — <b>없으면 401</b>({@link #currentPlayerRef()})과 <b>없어도 되는</b>
 * ({@link #currentPlayerRefIfAuthenticated()}). 둘을 하나로 합치지 않는 것이 요점이다: 합치면
 * {@code null} 을 반환하는 메서드 하나가 남고, <b>주인이 필요한 경로가 그것을 조용히 통과한다.</b>
 */
public interface PlayerRefResolver {

	/**
	 * 주인이 있어야 하는 경로가 쓴다.
	 *
	 * @return 현재 요청 주체의 {@code player_ref}
	 * @throws com.neowadaeum.common.error.ApiException {@code UNAUTHENTICATED} — 인증되지 않은 요청
	 */
	UUID currentPlayerRef();

	/**
	 * 인증 밖으로 열린 조회가 쓴다 (§13-54, 이슈 #306).
	 *
	 * <p><b>익명을 예외로 다루지 않는다.</b> 이 경로들에서 익명은 정상이며, 응답은 개인 필드를
	 * 비운 채 나간다 — 그 판단은 서비스가 한다.
	 *
	 * @return 인증되지 않았으면 비어 있다
	 */
	Optional<UUID> currentPlayerRefIfAuthenticated();
}
