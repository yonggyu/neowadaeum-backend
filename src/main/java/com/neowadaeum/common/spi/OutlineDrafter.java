package com.neowadaeum.common.spi;

/**
 * 챕터·엔딩 초안 SPI (R7.14, B-52).
 *
 * <p><b>{@link SafetyClassifier} 와 같은 모양이다.</b> {@code authoring} 은 AI 를 부르지만
 * <b>어느 벤더가 그 뒤에 있는지 알지 못한다</b> — 그 경계를 지키는 방법이 이 인터페이스다.
 *
 * <p><b>실패를 감추지 않는다.</b> 초안을 만들지 못했으면 만들지 못한 것이다 — 빈 초안을
 * 돌려주면 작성자는 <b>AI 가 아무 생각이 없었다</b>고 읽는다.
 *
 * <p>구현은 저비용 모델을 쓴다 (R3.6). 어느 모델인지는 이 계약의 관심사가 아니다.
 */
public interface OutlineDrafter {

	/**
	 * 초안을 만든다.
	 *
	 * @throws RuntimeException Provider 호출 실패·시간 초과. 호출자가 사용자에게 옮긴다
	 */
	OutlineDraft draft(OutlineDraftRequest request);
}
