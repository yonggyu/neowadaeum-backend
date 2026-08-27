package com.neowadaeum.authoring.precheck;

import com.neowadaeum.common.support.TextNormalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * 정규화된 문자열과 <b>원문에서의 자리</b> (R8.2, R2.5).
 *
 * <p><b>정규화와 밑줄은 서로 어긋난다.</b> 대조는 정규화 값끼리 하는데(R2.5) 밑줄은 <b>원문
 * 위치</b>에 그어야 한다 — 공백을 지우고 자모를 분해한 뒤의 인덱스는 원문의 인덱스가 아니다.
 * 그 대응을 만드는 것이 이 클래스다.
 *
 * <p><b>대조에 쓰는 값은 언제나 {@link TextNormalizer} 의 것이다.</b> 자리 추적을 위해 글자마다
 * 따로 정규화하지만, 그 결과가 통짜 정규화와 다르면 <b>대조가 판정기와 갈라진다</b> — 그래서
 * 두 값을 비교하고, 다르면 자리 추적을 포기하되 <b>대조는 그대로 한다</b> (§13-33).
 *
 * <p>자리를 포기한 경우 {@link #spanOf} 는 필드 전체를 가리킨다. 밑줄이 넓어질 뿐 <b>놓치지는
 * 않는다</b> — 반대 방향(자리는 정확한데 못 잡음)이 훨씬 나쁘다.
 */
final class NormalizedText {

	private final String raw;

	private final String value;

	/** 정규화 문자 하나마다 그것이 온 원문 인덱스. 자리 추적을 포기했으면 비어 있다. */
	private final int[] sourceIndex;

	private NormalizedText(String raw, String value, int[] sourceIndex) {
		this.raw = raw;
		this.value = value;
		this.sourceIndex = sourceIndex;
	}

	static NormalizedText of(String raw) {
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
	String value() {
		return this.value;
	}

	/**
	 * 정규화 위치 {@code [from, to)} 에 해당하는 <b>원문</b> 구간.
	 *
	 * <p>자리를 추적하지 못했으면 필드 전체를 가리킨다.
	 */
	int[] spanOf(int from, int to) {
		if (this.sourceIndex.length == 0 || to > this.sourceIndex.length || from >= to) {
			return new int[] { 0, this.raw.length() };
		}
		return new int[] { this.sourceIndex[from], this.sourceIndex[to - 1] + 1 };
	}
}
