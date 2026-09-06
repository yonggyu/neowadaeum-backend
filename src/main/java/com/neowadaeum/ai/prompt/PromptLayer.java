package com.neowadaeum.ai.prompt;

import com.neowadaeum.common.support.SummaryBudget;

/**
 * 프롬프트 레이어 (§5.1, R5.0, B-20).
 *
 * <p><b>선언 순서가 조립 순서다.</b> 순서를 바꾸는 것은 프롬프트를 바꾸는 것이며, 골든 파일 테스트가
 * 그것을 diff 로 드러낸다.
 *
 * <p><b>플랫폼 레이어와 작품 레이어를 타입으로 구분한다</b> (I-7). {@code SYSTEM} · {@code OUTPUT_SPEC}
 * 은 코드가 소유하고 작품이 덮어쓸 수 없다 — 값을 무시하는 것이 아니라 <b>작품이 채울 자리를 두지
 * 않는다</b>. {@link PromptContext} 에 그 두 레이어의 필드가 없는 것이 그 구조다.
 */
public enum PromptLayer {

	/** 플랫폼. 세이프티·등급 지시가 여기 있다 (R5.0). 작품이 덮어쓸 수 없다 (I-7). */
	SYSTEM(BudgetGroup.FOUNDATION, true),

	/** 작품. {@code story_version.world_prompt} 가 소유한다. */
	WORLD(BudgetGroup.FOUNDATION, false),

	/** 작품. {@code character.persona_prompt} 가 소유한다. */
	CHARACTER(BudgetGroup.FOUNDATION, false),

	/** 서버가 만든 현재 상태 (§4.1). */
	GAME_STATE(BudgetGroup.GAME_STATE, false),

	/**
	 * 플랫폼. {@code state_schema} 가 선언한 이름 (§13-76, #367). 작품이 덮어쓸 수 없다 (I-7).
	 *
	 * <p><b>{@code GAME_STATE} 바로 뒤다.</b> 앞은 <b>지금 값</b>이고 여기는 <b>쓸 수 있는 이름</b>
	 * 이며, 둘을 떼어 놓으면 모델이 값에 없는 이름을 <b>존재하지 않는 것</b>으로 읽는다 — 그것이
	 * 이 레이어가 생긴 이유 그대로다.
	 */
	STATE_VOCABULARY(BudgetGroup.STATE_VOCABULARY, true),

	/** 오래된 턴의 압축 (R4.5). */
	SUMMARY(BudgetGroup.SUMMARY, false),

	/** 최근 턴 원문 (R4.7). <b>예산이 모자랄 때 가장 먼저 줄어드는 레이어다.</b> */
	RECENT_TURNS(BudgetGroup.RECENT_TURNS, false),

	/** 이번 턴에 사용자가 고른 선택지. 서버가 저장해 둔 본문이며 클라이언트가 보낸 텍스트가 아니다 (I-1). */
	USER_ACTION(BudgetGroup.INSTRUCTION, false),

	/** 플랫폼. 출력 스키마 지시 (§5.2). 작품이 덮어쓸 수 없다 (I-7). */
	OUTPUT_SPEC(BudgetGroup.INSTRUCTION, true);

	private final BudgetGroup budgetGroup;

	private final boolean platform;

	PromptLayer(BudgetGroup budgetGroup, boolean platform) {
		this.budgetGroup = budgetGroup;
		this.platform = platform;
	}

	public BudgetGroup budgetGroup() {
		return this.budgetGroup;
	}

	/** 작품이 덮어쓸 수 없는 레이어인가 (I-7, R5.0). */
	public boolean isPlatform() {
		return this.platform;
	}

	/**
	 * 토큰 상한이 걸리는 단위 (§4.3).
	 *
	 * <p><b>레이어 하나가 아니라 묶음에 상한이 있다.</b> §4.3 의 표가 그렇게 되어 있다 — {@code SYSTEM}
	 * · {@code WORLD} · {@code CHARACTER} 는 합쳐서 1,200 이고 각각의 몫이 정해져 있지 않다.
	 */
	public enum BudgetGroup {

		/** SYSTEM + WORLD + CHARACTER. */
		FOUNDATION(1_200),

		GAME_STATE(300),

		/**
		 * 선언된 이름 목록 (§13-76 {@code [결정 필요]}, §13-82).
		 *
		 * <p><b>200 에서 175 로 줄었다</b> (§13-82). 이 레이어가 인쇄하던 연산자 표기가
		 * {@code OUTPUT SPEC} 으로 갔고, <b>예산은 문구를 따라간다</b> — 총합 4,000 은 그대로이므로
		 * {@link #INSTRUCTION} 이 같은 만큼 늘었다. §4.3 의 다른 숫자는 여전히 손대지 않는다.
		 *
		 * <p><b>이름의 자리는 오히려 넓어졌다.</b> 갈래 머리표가
		 * {@code flags.add / flags.remove} 에서 {@code flags} 로 짧아졌기 때문이다 — 줄어든 것은
		 * 상한이지 <b>이름이 쓸 수 있는 몫</b>이 아니다.
		 */
		STATE_VOCABULARY(175),

		SUMMARY(SummaryBudget.MAX_TOKENS),

		RECENT_TURNS(1_500),

		/**
		 * USER ACTION + OUTPUT SPEC.
		 *
		 * <p><b>200 에서 225 로 늘었다</b> (§13-82). {@code stateChanges} 의 연산자 표기가 여기로
		 * 왔고, 늘어난 25 는 {@link #STATE_VOCABULARY} 에서 온 것이다 — <b>총합 4,000 은 그대로다.</b>
		 */
		INSTRUCTION(225);

		private final int maxTokens;

		BudgetGroup(int maxTokens) {
			this.maxTokens = maxTokens;
		}

		public int maxTokens() {
			return this.maxTokens;
		}
	}
}
