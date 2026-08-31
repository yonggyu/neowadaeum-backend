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

		// 2단은 **원문**을 본다. 1단이 가린 본문을 넘기면 분류기가 보는 것이 사람이 읽을 것과
		// 같아지지만, 마스킹은 정보를 지울 뿐 더하지 않으므로 원문이 통과한 판정은 가린 본문에도
		// 그대로 성립한다. 반대로 가린 뒤에 넘기면 **판정 순서가 마스킹 성공 여부에 묶인다.**
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

		SafetyOutcome outcome = CategoryPolicy.decide(all);
		if (outcome != SafetyOutcome.MASKED) {
			return new SafetyJudgement(outcome, all);
		}
		return masked(first, semantic, all);
	}

	/**
	 * 마스킹은 <b>1단이 찾은 자리에만</b> 붙는다 (§9.2, §13-21).
	 *
	 * <p>2단이 같은 카테고리를 돌려줬다면 그것은 <b>블록리스트에 적혀 있지 않은 무언가</b>를 봤다는
	 * 뜻이고, 그 자리는 아무도 모른다. 분류기는 카테고리만 돌려주며 위치를 받는 계약을 만들지
	 * 않았다 — 모델이 말한 offset 을 믿고 본문을 잘라내면 <b>모델이 서버의 편집기가 된다.</b>
	 *
	 * <p>그래서 이 경우는 가리지 않고 <b>결과를 폐기한다.</b> 재생성은 오케스트레이터가 1회만
	 * 수행하며, 다시 걸리면 차단이다 (fail-closed).
	 */
	private static SafetyJudgement masked(SafetyJudgement first, Set<SafetyCategory> semantic,
			Set<SafetyCategory> all) {

		if (CategoryPolicy.anyMasked(semantic) || first.masked() == null) {
			return new SafetyJudgement(SafetyOutcome.REGENERATE, all);
		}
		return new SafetyJudgement(SafetyOutcome.MASKED, all, first.masked());
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
