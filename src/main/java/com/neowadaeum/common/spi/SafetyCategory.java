package com.neowadaeum.common.spi;

import java.util.Locale;

/**
 * 세이프티 카테고리와 그 정책 (§9.2).
 *
 * <p><b>왜 {@code common/spi} 에 있는가.</b> 판정하는 쪽(safety)과 블록리스트를 소유한 쪽
 * (authoring)이 같은 어휘를 써야 하는데, 둘은 서로를 참조하지 않는다 (§5.4, ADR-0002).
 * 카테고리는 그 둘 사이의 <b>계약</b>이므로 SPI 와 같은 자리에 둔다.
 *
 * <p>정책이 카테고리에 붙어 있는 이유는 <b>둘이 함께 정해지기 때문</b>이다. 어느 카테고리가
 * 즉시차단인지를 별도 표로 두면 카테고리를 늘릴 때 그 표를 잊는다 — 그때 기본값이 무엇이든
 * 조용히 적용된다.
 */
public enum SafetyCategory {

	/** 미성년자 성적 콘텐츠. */
	MINOR_SEXUAL(SafetyPolicy.BLOCK_IMMEDIATELY),

	/** 실존 인물 성적·명예훼손. */
	REAL_PERSON_HARM(SafetyPolicy.BLOCK_IMMEDIATELY),

	/** 비동의 성행위. */
	NON_CONSENSUAL(SafetyPolicy.BLOCK_IMMEDIATELY),

	/** 기존 IP 캐릭터·설정 복제. L0/L1 은 차단, L2 는 재생성이다 (§9.2). */
	IP_REPLICATION(SafetyPolicy.REGENERATE_ONCE),

	/** 15세 등급 초과 — 선정성·폭력성. */
	RATING_EXCEEDED(SafetyPolicy.REGENERATE_ONCE),

	/** 혐오 표현. */
	HATE_SPEECH(SafetyPolicy.REGENERATE_ONCE),

	/**
	 * 타인 개인정보.
	 *
	 * <p>§9.2 는 생성물에 대해 <b>마스킹 후 통과</b>를 규정한다. 마스킹은 탐지 위치(span)를
	 * 알아야 하므로 규칙 기반 대조만으로는 할 수 없다 — 구현 전까지 이 카테고리가 판정에 나오면
	 * 조용히 통과시키지 않고 실패시킨다 (§0.2).
	 */
	THIRD_PARTY_PERSONAL_DATA(SafetyPolicy.MASK);

	private final SafetyPolicy policy;

	SafetyCategory(SafetyPolicy policy) {
		this.policy = policy;
	}

	public SafetyPolicy policy() {
		return this.policy;
	}

	/** 즉시차단 카테고리는 <b>재생성 없이</b> 차단한다 (§9.2, B-30 DoD). */
	public boolean blocksImmediately() {
		return this.policy == SafetyPolicy.BLOCK_IMMEDIATELY;
	}

	/**
	 * 판정기와 주고받는 표기 (B-30).
	 *
	 * <p>열거형 이름을 그대로 쓰지 않는 것은 {@code AiPurpose} 와 같은 이유다 — 와이어 표기가
	 * 코드 이름에 묶여 있으면 이름을 정리하는 순간 계약이 조용히 바뀐다.
	 */
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * 표기를 카테고리로 되돌린다.
	 *
	 * <p><b>모르는 표기는 실패다.</b> 판정기가 우리가 모르는 이름을 돌려줬다면 <b>무엇을 봤는지
	 * 모르는 상태</b>이고, 그것을 빈 집합으로 바꾸면 판정 실패가 통과로 둔갑한다 (fail-closed).
	 */
	public static SafetyCategory fromWireValue(String value) {
		for (SafetyCategory category : values()) {
			if (category.wireValue().equals(value)) {
				return category;
			}
		}
		throw new SafetyClassificationFailedException("unknown safety category in classifier response");
	}
}
