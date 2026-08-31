package com.neowadaeum.safety.l2;

import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.SafetyPolicy;
import com.neowadaeum.common.support.NormalizedText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 걸린 자리를 가린다 (§9.2 — 타인 개인정보는 생성물에서 마스킹 후 통과).
 *
 * <p><b>서버가 자리를 아는 것만 가린다.</b> 1단 블록리스트 대조는 어떤 항목이 어디에 걸렸는지를
 * 결정론적으로 말할 수 있다 — 정규화 위치를 원문 위치로 되돌리는 대응이 있기 때문이다
 * ({@link NormalizedText}). 그 대응이 성립하지 않으면 <b>가리지 않는다.</b>
 *
 * <p><b>2단 의미 분류는 여기에 오지 않는다.</b> 분류기는 카테고리만 돌려주며(§13-21) 위치를 받는
 * 계약을 만들지 않았다. 모델이 말한 offset 을 믿고 본문을 잘라내면 <b>모델이 서버의 편집기가
 * 된다</b> — 그 방향의 오류는 되돌릴 수 없다. 자리를 모르는 탐지는 마스킹이 아니라 재생성으로
 * 처리한다.
 *
 * <p><b>가린 뒤 다시 대조한다.</b> 가렸다고 믿는 것과 실제로 지워진 것은 다르다 — 특히 대조는
 * 받은 문자열을 <b>전부 이어 붙여</b> 수행하므로(#84) 문단 경계에 걸친 표현은 어느 한 문단
 * 안에서 찾을 수 없다. 그런 탐지는 <b>가리지 못한 것</b>이고, 검증이 그것을 잡는다.
 */
final class PersonalDataMasker {

	/**
	 * 가린 자리에 남는 표시.
	 *
	 * <p><b>길이를 보존하지 않는다.</b> 지운 문자열의 길이는 그 자체로 단서다 — 몇 글자였는지를
	 * 남기면 후보를 좁혀 준다 (S-11).
	 */
	static final String MARK = "○○○";

	private PersonalDataMasker() {
	}

	/**
	 * 마스킹 정책 카테고리의 항목들을 가린다.
	 *
	 * @return 가린 본문. <b>{@code null} 이면 가리지 못했다</b> — 부르는 쪽은 통과시키지 않는다
	 */
	static MaskedText mask(List<String> paragraphs, List<String> choices, List<BlocklistEntry> entries) {
		List<BlocklistEntry> maskable = entries.stream()
				.filter(entry -> entry.category().policy() == SafetyPolicy.MASK)
				.toList();

		if (maskable.isEmpty()) {
			// 마스킹 대상 카테고리가 걸렸는데 그 항목이 블록리스트에 없다 — 1단이 찾은 것이
			// 아니라는 뜻이고, 그러면 자리를 아는 곳이 없다.
			return null;
		}

		List<String> maskedParagraphs = maskAll(paragraphs, maskable);
		List<String> maskedChoices = maskAll(choices, maskable);
		if (maskedParagraphs == null || maskedChoices == null) {
			return null;
		}

		// 검증 — 판정기와 같은 대조를 가린 결과에 다시 건다.
		if (CategoryPolicy.anyMasked(RuleBasedSafetyJudge.hitsIn(
				RuleBasedSafetyJudge.matchTarget(maskedParagraphs, maskedChoices), maskable))) {
			return null;
		}

		return new MaskedText(maskedParagraphs, maskedChoices);
	}

	/** @return {@code null} 이면 이 목록 중 하나에서 자리를 찾지 못했다 */
	private static List<String> maskAll(List<String> texts, List<BlocklistEntry> maskable) {
		if (texts == null) {
			return List.of();
		}
		List<String> masked = new ArrayList<>(texts.size());
		for (String text : texts) {
			String one = maskOne(text, maskable);
			if (one == null) {
				return null;
			}
			masked.add(one);
		}
		return masked;
	}

	private static String maskOne(String raw, List<BlocklistEntry> maskable) {
		if (raw == null || raw.isEmpty()) {
			return raw;
		}

		NormalizedText text = NormalizedText.of(raw);
		List<int[]> spans = new ArrayList<>();

		for (BlocklistEntry entry : maskable) {
			int from = text.value().indexOf(entry.normalizedValue());
			while (from >= 0) {
				int to = from + entry.normalizedValue().length();
				if (!text.tracked()) {
					// 정규화 자리 추적이 성립하지 않는다. 필드 전체를 지우는 대신 가리기를
					// 포기한다 — 밑줄과 달리 마스킹은 넓게 잡는 쪽이 안전하지 않다.
					return null;
				}
				spans.add(text.spanOf(from, to));
				from = text.value().indexOf(entry.normalizedValue(), to);
			}
		}

		if (spans.isEmpty()) {
			return raw;
		}
		return replace(raw, merge(spans));
	}

	/** 겹치거나 맞닿은 구간을 합친다. 합치지 않으면 표시가 겹쳐 붙는다. */
	private static List<int[]> merge(List<int[]> spans) {
		List<int[]> sorted = new ArrayList<>(spans);
		sorted.sort(Comparator.comparingInt(span -> span[0]));

		List<int[]> merged = new ArrayList<>();
		for (int[] span : sorted) {
			if (!merged.isEmpty() && span[0] <= merged.getLast()[1]) {
				merged.getLast()[1] = Math.max(merged.getLast()[1], span[1]);
				continue;
			}
			merged.add(new int[] { span[0], span[1] });
		}
		return merged;
	}

	private static String replace(String raw, List<int[]> spans) {
		StringBuilder masked = new StringBuilder(raw.length());
		int cursor = 0;
		for (int[] span : spans) {
			masked.append(raw, cursor, span[0]).append(MARK);
			cursor = span[1];
		}
		return masked.append(raw, cursor, raw.length()).toString();
	}
}
