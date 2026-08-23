package com.neowadaeum.play.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 턴 요청 본문 (§4.3-1).
 *
 * <p><b>사용자 입력면은 이 둘뿐이다.</b> 플레이 중 자유입력이 없고(I-18), 선택지는 서버가 발급한
 * {@code choiceId} 로만 제출된다 (I-1). <b>선택지 텍스트를 받는 필드를 두지 않는다</b> — 받아 놓고
 * 무시하는 것과 받을 자리가 없는 것은 다르다.
 *
 * @param choiceId 직전 턴이 발급한 선택지 식별자
 * @param turnNo   <b>지금 화면에 떠 있는 턴</b>의 번호. 낙관적 잠금 키다 (I-6, §4.3 턴 번호 계약)
 * @param idempotencyKey 본문이 아니라 <b>헤더</b>에서 온다 (R6.2). 컨트롤러가 채운다
 */
public record TurnRequestBody(@NotBlank String choiceId, @Min(0) int turnNo, String idempotencyKey) {

	/** 헤더로 들어온 {@code Idempotency-Key} 를 실어 준다 (R6.2). 없으면 {@code null} 이다. */
	public TurnRequestBody withIdempotencyKey(String key) {
		return new TurnRequestBody(this.choiceId(), this.turnNo(), key);
	}
}
