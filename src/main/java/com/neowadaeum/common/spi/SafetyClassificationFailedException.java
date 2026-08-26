package com.neowadaeum.common.spi;

/**
 * 의미 기반 분류를 수행하지 못했다 (B-30).
 *
 * <p><b>"안전하다"가 아니라 "모른다"이다.</b> 호출자는 이것을 통과로 바꾸지 않는다 — 세이프티에서
 * fail-open 은 장애가 곧 검수 우회다 (ADR-0002 와 같은 성질). 판정하지 못한 응답은 차단한다.
 *
 * <p><b>메시지에 판정 대상 원문을 담지 않는다</b> (S-3). 예외는 로그로 흐른다.
 */
public class SafetyClassificationFailedException extends RuntimeException {

	public SafetyClassificationFailedException(String message) {
		super(message);
	}
}
