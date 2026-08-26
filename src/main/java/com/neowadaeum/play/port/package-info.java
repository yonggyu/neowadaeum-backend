/**
 * 턴 생성 포트 — <b>{@code play} 가 소유하는 계약</b> (ADR-0006, #84).
 *
 * <p><b>이 패키지는 {@code play} 모듈이 밖으로 내미는 요구다.</b> 다른 모듈이 제공한 API 를
 * {@code play} 가 받아 쓰는 것이 아니라, <b>{@code play} 가 필요한 모양을 정의하고 {@code ai} 가
 * 그것을 구현한다.</b> 의존은 {@code ai → play :: port} 한 방향이며 {@code play} 는 {@code ai} 를
 * 알지 못한다.
 *
 * <p><b>왜 방향을 뒤집었는가</b> (ADR-0006, ADR-0005 대체). {@link GeneratedTurn} 의 형태는
 * Provider 의 사정이 아니라 <b>{@code play} 가 저장하고 응답할 형태</b>에서 나온다. 계약이
 * {@code ai} 에 있던 동안 그 어긋남은 {@code TurnResult.narrative} 통 문자열로 나타났고,
 * {@code TurnPipeline} 이 그것을 {@code List.of(...)} 로 감싸 <b>R5.1 이 금지한 1개짜리 배열</b>을
 * 저장했다.
 *
 * <p><b>서버 권한 값은 이 계약에 자리가 없다.</b> {@code chapter} · {@code turn} (I-9) ·
 * {@code choiceId} (I-1) · {@code disabled} (I-11) 는 어느 타입에도 담기지 않는다. 값을 무시하는
 * 코드를 두는 것이 아니라 <b>받을 자리가 없는 것</b>이 보장이다.
 */
@NamedInterface("port")
package com.neowadaeum.play.port;

import org.springframework.modulith.NamedInterface;
