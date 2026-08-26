package com.neowadaeum.play.port;

import java.util.UUID;

/**
 * Provider 에 넘기는 턴 생성 입력 (S-3).
 *
 * <p><b>I-3 — 회원 식별정보를 담지 않는다.</b> 이메일·이름·소셜 ID·IP·생년월일·{@code player_ref}
 * 어느 것도 필드로 존재하지 않는다. 필터링으로 지우는 것이 아니라 <b>담을 자리를 만들지 않는 것</b>이
 * 이 레코드의 설계 의도다. {@code sessionId} 도 두지 않았다 — S-3 의 결정론 조회에 필요 없고,
 * 없는 필드는 샐 수 없다.
 *
 * <p><b>턴 번호 계약 (§4.3, §13-14-e)</b> — {@code turnNo} 는 <b>요청 시점의 현재 턴</b>이다.
 * 생성되는 턴은 {@code turnNo + 1} 이다. 혼동하면 오프바이원이 파이프라인 전체로 번진다.
 *
 * <p><b>{@code context} 는 B-22 에서 늘었다.</b> 그전까지 이 레코드는 <b>참조만</b> 날랐고, 그것으로는
 * 어댑터가 요청 본문을 만들 수 없다 — 프롬프트의 재료가 전부 {@code play} · {@code catalog} 에 있기
 * 때문이다. 재료가 계약을 타고 건너가는 이유는 {@link GenerationContext} 에 적었다.
 *
 * @param storyVersionRef 작품 버전 참조. 스키마 간 FK 가 아니라 애플리케이션 레벨 UUID 참조다 (§5.3)
 * @param turnNo          요청 시점의 현재 턴 번호. 세션 첫 턴 생성은 {@code 0}
 * @param chosenChoiceOrder 직전 턴에서 고른 선택지의 표시 순서. 세션 첫 턴이면 {@code null}
 * @param context         이 턴을 쓰는 데 필요한 재료 (§5.1)
 */
public record TurnRequest(UUID storyVersionRef, int turnNo, Integer chosenChoiceOrder, GenerationContext context) {

	public TurnRequest {
		if (context == null) {
			throw new IllegalArgumentException("generation context is required");
		}
		if (storyVersionRef == null) {
			throw new IllegalArgumentException("storyVersionRef is required");
		}
		if (turnNo < 0) {
			throw new IllegalArgumentException("turnNo must not be negative");
		}
		if (turnNo == 0 && chosenChoiceOrder != null) {
			throw new IllegalArgumentException("the opening turn has no chosen choice");
		}
		if (turnNo > 0 && chosenChoiceOrder == null) {
			throw new IllegalArgumentException("a non-opening turn requires the chosen choice order");
		}
	}

	/** 세션의 첫 턴 생성 요청. */
	public static TurnRequest opening(UUID storyVersionRef, GenerationContext context) {
		return new TurnRequest(storyVersionRef, 0, null, context);
	}
}
