package com.neowadaeum.common.spi;

import java.util.Set;

/**
 * 의미 기반 세이프티 분류 (R9.2 의 2단, B-30).
 *
 * <p><b>왜 {@code common/spi} 인가.</b> 판정하는 쪽은 {@code safety} 이고 모델을 부를 수 있는 쪽은
 * {@code ai} 인데, <b>{@code safety} 는 {@code ai} 를 참조하지 않는다</b> (§5.4). 그 관계가 I-13 의
 * 구조적 근거다 — provider 를 모르는 판정기는 provider 를 바꿔도 그대로 판정한다. 블록리스트 조회를
 * {@link BlocklistQuery} 로 뒤집은 것과 같은 형태다 (ADR-0002).
 *
 * <p><b>I-12 는 이 seam 하나로 지켜지지 않는다.</b> 구현이 어느 모델을 부르는지가 함께 정해져야
 * 한다 — {@code ai} 는 <b>검수 용도로 설정된 모델</b>을 부르며 (R3.6, {@code AiPurpose.SAFETY}),
 * 그 설정이 없으면 판정하지 않고 실패한다. 생성 모델을 빌려 쓰는 경로를 두지 않는다.
 *
 * <p><b>1단을 대체하지 않는다.</b> 정규화 + 블록리스트 대조(S-8)는 그대로 먼저 돌고, 이것은 그
 * 뒤에 온다. 원문이 2단 구성을 요구한 이유가 <b>서로가 못 잡는 것을 잡기 때문</b>이다 (R9.2).
 */
public interface SafetyClassifier {

	/**
	 * 걸린 카테고리를 돌려준다. 아무것도 걸리지 않으면 빈 집합이다.
	 *
	 * <p><b>결과는 카테고리뿐이다.</b> 신뢰도·위치를 받지 않는 것은 지금 그것을 쓸 곳이 없기
	 * 때문이다 — 위치가 필요한 것은 §9.2 의 마스킹 정책 하나이고 그것은 미구현이다. 쓰지 않는 값을
	 * 계약에 넣으면 구현마다 그럴듯한 숫자를 지어내게 된다.
	 *
	 * @throws SafetyClassificationFailedException 판정을 수행하지 못했다. <b>통과가 아니다</b>
	 */
	Set<SafetyCategory> classify(SafetyClassificationRequest request);
}
