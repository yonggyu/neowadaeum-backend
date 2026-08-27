package com.neowadaeum.common.web;

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
 */
public interface PlayerRefResolver {

	/**
	 * @return 현재 요청 주체의 {@code player_ref}
	 */
	UUID currentPlayerRef();
}
