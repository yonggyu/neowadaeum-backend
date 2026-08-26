package com.neowadaeum.safety.l2;

import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationFailedException;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.common.spi.SafetyClassifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L2 판정 — R9.2 의 2단 구성 (B-30).
 *
 * <p><b>1단이 못 잡는 것을 2단이 잡는다.</b> 정규화 + 블록리스트 대조는 <b>적혀 있는 것</b>만
 * 잡는다 (S-8). 적혀 있지 않은 표현으로 같은 것을 말하는 응답은 1단을 그대로 지나간다 — 원문이
 * 탐지를 2단으로 규정한 이유가 그것이다.
 *
 * <p><b>순서가 정책이다.</b>
 *
 * <table border="1">
 * <caption>단계별 처리</caption>
 * <tr><th>1단 결과</th><th>2단</th><th>근거</th></tr>
 * <tr><td>즉시차단</td><td><b>부르지 않는다</b></td><td>결과가 이미 가장 강하다. 부르면 <b>비용만 는다</b></td></tr>
 * <tr><td>재생성</td><td>부른다</td><td>2단이 즉시차단 카테고리를 찾으면 <b>재생성이 차단으로 올라간다</b></td></tr>
 * <tr><td>통과</td><td>부른다</td><td>여기가 2단의 본래 자리다</td></tr>
 * </table>
 *
 * <p><b>2단이 판정하지 못하면 차단한다</b> (fail-closed). "판정하지 못했다"는 "안전하다"가
 * 아니며, 세이프티에서 fail-open 은 장애가 곧 검수 우회다 (ADR-0002 와 같은 성질). 응답은 L2 를
 * 통과하기 전까지 사용자에게 도달하지 않는다 (I-2).
 *
 * <p><b>I-12 · I-13 은 여기서도 유지된다.</b> 이 클래스는 {@code ai} 를 참조하지 않는다 — 2단은
 * {@link SafetyClassifier} 라는 {@code common/spi} 인터페이스이며, 어느 벤더가 그 뒤에 있는지
 * 판정기는 알지 못한다.
 */
public class SafetyL2Judge {

	private static final Logger log = LoggerFactory.getLogger(SafetyL2Judge.class);

	private final RuleBasedSafetyJudge rules;

	private final SafetyClassifier classifier;

	/**
	 * @param rules      1단 — 정규화 + 블록리스트 대조 (S-8)
	 * @param classifier 2단 — 의미 기반 분류 (R9.2). <b>{@code null} 이면 생성에 실패한다</b>:
	 *                   구현 빈이 없으면 부팅이 멈추는 것이 의도다 (ADR-0002 fail-fast)
	 */
	public SafetyL2Judge(RuleBasedSafetyJudge rules, SafetyClassifier classifier) {
		if (rules == null || classifier == null) {
			throw new IllegalArgumentException(
					"L2 needs both stages — a missing classifier must not silently reduce L2 to stage one (R9.2)");
		}
		this.rules = rules;
		this.classifier = classifier;
	}

	/** 본문과 선택지를 함께 판정한다 (§9.1 의 L2 행). */
	public SafetyJudgement judge(List<String> paragraphs, List<String> choices) {
		SafetyJudgement first = this.rules.judge(paragraphs, choices);
		if (first.blocked()) {
			// 이미 가장 강한 결과다. 여기서 2단을 부르면 결과는 그대로이고 비용만 는다 (§9.2).
			return first;
		}

		List<String> texts = texts(paragraphs, choices);
		if (texts.isEmpty()) {
			// 판정할 문자열이 없다. 그런 응답은 파서가 먼저 거부하지만(R5.1), 여기서 빈 요청을
			// 만들어 부르지는 않는다.
			return first;
		}

		Set<SafetyCategory> semantic;
		try {
			semantic = this.classifier.classify(new SafetyClassificationRequest(texts));
		}
		catch (SafetyClassificationFailedException ex) {
			// 판정 대상 원문을 남기지 않는다 (S-3). 남기는 것은 "판정하지 못해 막았다"까지다.
			log.warn("stage two did not complete — blocking as a closed failure (R9.2)");
			return new SafetyJudgement(SafetyOutcome.BLOCK, first.categories());
		}

		Set<SafetyCategory> all = new LinkedHashSet<>(first.categories());
		all.addAll(semantic);
		return new SafetyJudgement(CategoryPolicy.decide(all), all);
	}

	/**
	 * 판정 대상 문자열.
	 *
	 * <p><b>선택지도 함께 넘긴다.</b> 1단이 둘을 나눠 보지 않는 것과 같은 이유다 — 선택지 텍스트도
	 * 사용자에게 도달한다.
	 */
	private static List<String> texts(List<String> paragraphs, List<String> choices) {
		List<String> texts = new ArrayList<>();
		if (paragraphs != null) {
			paragraphs.stream().filter(text -> text != null && !text.isBlank()).forEach(texts::add);
		}
		if (choices != null) {
			choices.stream().filter(text -> text != null && !text.isBlank()).forEach(texts::add);
		}
		return texts;
	}
}
