package com.neowadaeum.common.spi;

import java.util.Collection;

/**
 * 선언된 상태 어휘가 프롬프트 예산에 들어가는가 (§13-76, #367).
 *
 * <p><b>왜 SPI 인가.</b> 이 값을 아는 것은 {@code ai} 다 — {@code STATE VOCABULARY} 레이어의
 * 머리글도, 연산자 표기도, 묶음 상한도 거기 있다. 물어야 하는 것은 {@code authoring} 이다:
 * 원고를 저장하는 자리가 <b>작성자가 아직 고칠 수 있는 마지막 지점</b>이다. {@code authoring} 의
 * 허용 의존에 {@code ai} 는 없고, 열 이유도 없다 — {@link BlocklistQuery} · {@link OutlineDrafter}
 * 와 같은 형태로 계약을 {@code common} 에 두고 <b>구현을 그 값을 소유한 모듈에</b> 둔다.
 *
 * <p><b>숫자를 건네지 않고 판정을 건넨다.</b> 상한을 알려 주면 {@code authoring} 이 그것을 복제하고,
 * 복제된 숫자는 {@code ai} 가 레이어 문구를 한 줄 고치는 날 조용히 어긋난다 — 그리고 그 어긋남은
 * <b>작성자의 작품이 한 턴도 돌지 않을 때</b>에야 드러난다.
 *
 * <p><b>구현은 실제 블록을 만들어 센다.</b> 예산을 모사하지 않는다 — 모사는 진짜와 갈라지고,
 * 갈라진 쪽이 통과시키면 이 게이트는 없는 것과 같다.
 */
public interface StateVocabularyBudget {

	/**
	 * @param numericPaths 수치 경로. {@code <그룹>.<이름>} 이며 {@code stateChanges} 의 키와 같은 표기다
	 * @param flags        플래그 이름
	 * @param inventory    아이템 이름
	 */
	Usage assess(Collection<String> numericPaths, Collection<String> flags, Collection<String> inventory);

	/**
	 * 얼마나 썼는가.
	 *
	 * <p><b>토큰 수가 아니라 비율이다.</b> 토큰은 작성자가 아는 단위가 아니고, 그 수를 응답으로
	 * 내보내면 계산 방식이 함께 나간다 (S-6). 비율은 <b>얼마나 줄여야 하는가</b>에 그대로 답한다.
	 *
	 * @param percentOfBudget 상한 대비 백분율. 100 이면 상한과 같다
	 */
	record Usage(int percentOfBudget) {

		public Usage {
			if (percentOfBudget < 0) {
				throw new IllegalArgumentException("percentOfBudget must not be negative");
			}
		}

		public boolean fits() {
			return this.percentOfBudget <= 100;
		}
	}
}
