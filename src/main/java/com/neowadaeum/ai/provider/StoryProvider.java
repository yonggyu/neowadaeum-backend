package com.neowadaeum.ai.provider;

import com.neowadaeum.play.port.TurnGenerationPort;

/**
 * AI 벤더 추상화 (§3, B-18).
 *
 * <p><b>{@code play} 가 소유한 턴 생성 계약을 확장한다</b> (ADR-0006). 벤더 seam 은
 * {@link TurnGenerationPort} 가 요구하는 것에 <b>AI 고유의 것</b>(능력 조회 · 요약 · 아웃라인)을
 * 더한 것이다. 이 관계 덕분에 어느 어댑터든 그대로 포트 구현이 되고, <b>변환 어댑터가 따로 필요
 * 없다.</b>
 *
 * <p><b>순수 DTO 만 주고받는다.</b> {@code ai} 모듈은 도메인 엔티티를 알지 못하며, 이것이 I-3 의
 * 구조적 보장이다 — 엔티티를 모르면 회원 식별정보를 실을 방법 자체가 없다. {@code play :: port} 가
 * 열려 있어도 거기에는 DTO 와 인터페이스뿐이라 이 성질은 바뀌지 않는다.
 *
 * <p><b>구현체는 어댑터다. 호출자가 어댑터를 직접 고르지 않는다</b> — 어느 것이 불릴지는 설정이
 * 정하고 {@code AiGateway} 가 그 결정을 수행한다 (R3.1, I-14). 세션은 생성 시 provider 에 고정된다
 * (I-4, R3.5).
 *
 * <p><b>미구현 메서드에 기본 구현을 주지 않는다.</b> 여기에 {@code default} 를 두면 새 어댑터가
 * 조용히 그것을 물려받고, 지원하지 않는 용도가 지원되는 것처럼 보인다. 어댑터마다 명시적으로
 * 답하게 하고, 아직 못 하는 것은 {@link UnsupportedOperationException} 을 던진다 (§0.2).
 */
public interface StoryProvider extends TurnGenerationPort {

	/**
	 * 이 구현이 무엇을 할 수 있는지 (§3).
	 *
	 * <p>호출자가 분기하는 값이다 — 재요청 횟수(R3.3) · 토큰 예산(§4.3) · {@code SYSTEM} 레이어
	 * 전달 방식이 여기서 갈린다.
	 */
	ProviderCapabilities capabilities();

	/**
	 * 오래된 턴을 요약으로 압축한다 (R4.5, R4.6).
	 *
	 * <p>턴 응답을 돌려준 <b>이후</b> 비동기로 수행한다. 사용자 대기 시간에 넣지 않는다.
	 *
	 * @throws UnsupportedOperationException 아직 이 용도를 구현하지 않은 어댑터 (B-34)
	 */
	String summarize(SummaryRequest request);

	/**
	 * UGC 작품의 챕터·엔딩 초안을 만든다 (§3, R7.15).
	 *
	 * <p>결과는 검수 대상이며 그대로 게시되지 않는다.
	 *
	 * @throws UnsupportedOperationException 아직 이 용도를 구현하지 않은 어댑터 (B-52)
	 */
	OutlineResult draftOutline(OutlineRequest request);
}
