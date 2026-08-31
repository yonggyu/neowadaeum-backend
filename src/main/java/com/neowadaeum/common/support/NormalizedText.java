package com.neowadaeum.common.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 정규화된 문자열과 <b>원문에서의 자리</b> (R8.2, R2.5, R9.2).
 *
 * <p><b>정규화와 자리는 서로 어긋난다.</b> 대조는 정규화 값끼리 하는데(R2.5) 자리는 <b>원문
 * 위치</b>로 말해야 한다 — 공백을 지우고 자모를 분해한 뒤의 인덱스는 원문의 인덱스가 아니다.
 * 그 대응을 만드는 것이 이 클래스다.
 *
 * <p><b>대조에 쓰는 값은 언제나 {@link TextNormalizer} 의 것이다.</b> 자리 추적을 위해 글자마다
 * 따로 정규화하지만, 그 결과가 통짜 정규화와 다르면 <b>대조가 판정기와 갈라진다</b> — 그래서
 * 두 값을 비교하고, 다르면 자리 추적을 포기하되 <b>대조는 그대로 한다</b> (§13-33).
 *
 * <p><b>자리를 포기했을 때 무엇을 하는지는 부르는 쪽이 정한다.</b> 두 용도의 안전한 방향이
 * 반대이기 때문이다.
 *
 * <ul>
 *   <li><b>밑줄</b>(L0 precheck) — {@link #spanOf} 가 필드 전체를 가리킨다. 넓게 그을 뿐
 *       <b>놓치지는 않는다</b>
 *   <li><b>마스킹</b>(L2, §9.2) — {@link #tracked()} 가 거짓이면 <b>가리지 않는다.</b> 자리를
 *       모르는 채로 지우면 본문을 임의로 훼손하게 되고, 그 방향의 오류는 되돌릴 수 없다
 * </ul>
 *
 * <p><b>왜 {@code common/support} 인가.</b> L0 은 입력에 밑줄을 긋고 L2 는 출력을 가린다 —
 * 하는 일은 다르지만 <b>"정규화 위치를 원문 위치로 되돌린다"</b> 는 하나다. 두 벌로 두면 정규화
 * 규칙이 바뀌는 날 한쪽만 따라간다.
 */
public final class NormalizedText {

	private final String raw;

	private final String value;

	/** 정규화 문자 하나마다 그것이 온 원문 인덱스. 자리 추적을 포기했으면 비어 있다. */
	private final int[] sourceIndex;

	private NormalizedText(String raw, String value, int[] sourceIndex) {
		this.raw = raw;
		this.value = value;
		this.sourceIndex = sourceIndex;
	}

	public static NormalizedText of(String raw) {
		String canonical = TextNormalizer.normalize(raw);

		StringBuilder tracked = new StringBuilder(canonical.length());
		List<Integer> origins = new ArrayList<>(canonical.length());
		for (int index = 0; index < raw.length(); index++) {
			String piece = TextNormalizer.normalize(String.valueOf(raw.charAt(index)));
			for (int i = 0; i < piece.length(); i++) {
				tracked.append(piece.charAt(i));
				origins.add(index);
			}
		}

		if (!canonical.contentEquals(tracked)) {
			// 글자마다 정규화한 결과가 통짜 정규화와 다르다 — 자리 추적을 포기한다.
			return new NormalizedText(raw, canonical, new int[0]);
		}
		return new NormalizedText(raw, canonical, origins.stream().mapToInt(Integer::intValue).toArray());
	}

	/** 대조에 쓰는 값. <b>언제나 통짜 정규화의 결과다.</b> */
	public String value() {
		return this.value;
	}

	/** 원문. */
	public String raw() {
		return this.raw;
	}

	/**
	 * 자리를 추적했는가.
	 *
	 * <p>거짓이면 {@link #spanOf} 는 <b>필드 전체</b>를 돌려준다. 그 값을 자리로 믿고 잘라내는
	 * 용도(마스킹)는 이 값을 먼저 확인해야 한다.
	 */
	public boolean tracked() {
		return this.sourceIndex.length > 0;
	}

	/**
	 * 정규화 위치 {@code [from, to)} 에 해당하는 <b>원문</b> 구간.
	 *
	 * <p>자리를 추적하지 못했으면 필드 전체를 가리킨다 ({@link #tracked()}).
	 */
	public int[] spanOf(int from, int to) {
		if (this.sourceIndex.length == 0 || to > this.sourceIndex.length || from >= to) {
			return new int[] { 0, this.raw.length() };
		}
		return new int[] { this.sourceIndex[from], this.sourceIndex[to - 1] + 1 };
	}
}
