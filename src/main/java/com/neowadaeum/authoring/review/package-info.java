/**
 * 제출과 검수 (§8.3, B-54).
 *
 * <p><b>승인이 곧 게시다</b> (R8.8) — 검수를 통과하면 버전이 발행되고 현재가 된다.
 *
 * <p><b>S-11 — 검수 비율·임계값을 코드·주석·응답 어디에도 적지 않는다.</b> 값을 알면 그 아래로
 * 관리할 수 있다 (§13-12).
 *
 * <p>{@code @NamedInterface} 로 <b>관리자에게만</b> 열린다 (B-55). {@code admin} 이 검수 큐를
 * 여는 유일한 길이며, 그 밖의 모듈은 여전히 이 패키지를 보지 못한다 — 검수 상태를 바꾸는
 * 경로가 늘어나는 순간 <b>승인 없이 열린 작품</b>이 생길 수 있다 (I-8).
 */
@NamedInterface("review")
package com.neowadaeum.authoring.review;

import org.springframework.modulith.NamedInterface;
