package com.neowadaeum.safety.l2;

import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.BlocklistQuery;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.support.TextNormalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 규칙 기반 L2 판정기 (B-30 축소 / S-8).
 *
 * <p><b>I-12 — 생성 모델과 별개의 판정기다.</b> 이 클래스는 어떤 모델도 호출하지 않는다. 자기
 * 검열에 의존하지 않는다는 것은 "생성한 쪽에게 검사를 맡기지 않는다"는 뜻이고, 그것을 지키는
 * 가장 단순한 방법은 판정 경로에 생성기를 두지 않는 것이다.
 *
 * <p><b>I-13 — provider 를 알지 못한다.</b> {@code safety} 는 {@code ai} 를 참조하지 않으므로
 * 무검열 로컬 모델을 붙여도 이 판정은 그대로 수행된다.
 *
 * <p><b>R9.2 의 1단이다.</b> 원문은 탐지를 "블록리스트 + 정규화 + 의미 기반 분류의 2단 구성"으로
 * 규정하며, 2단(의미 기반)은 {@link SafetyL2Judge} 가 이 뒤에 붙인다 (B-30). 그래도 <b>이것 하나로도
 * 실제로 차단한다</b> — 축소된 실물이지 스텁이 아니다 (§0.2, ADR-0004).
 *
 * <p><b>여기가 자리를 아는 유일한 단이다</b> (§9.2 마스킹). 블록리스트 대조는 어떤 항목이 어디에
 * 걸렸는지를 결정론적으로 말할 수 있고, 그래서 마스킹은 이 단의 탐지에만 붙는다
 * ({@link PersonalDataMasker}).
 *
 * <p><b>fail-closed</b> (ADR-0002) — 블록리스트 조회가 실패하면 통과시키지 않고 차단한다.
 * 블록리스트를 못 읽는 상태에서 통과시키면 블록리스트가 존재하지 않는 것과 같다.
 */
public class RuleBasedSafetyJudge {

	private static final Logger log = LoggerFactory.getLogger(RuleBasedSafetyJudge.class);

	private final BlocklistQuery blocklist;

	/**
	 * @param blocklist 조회 SPI. <b>{@code null} 이면 생성에 실패한다</b> — 스프링 주입 실패로
	 *                  부팅이 멈추는 것이 의도다 (ADR-0002 fail-fast)
	 */
	public RuleBasedSafetyJudge(BlocklistQuery blocklist) {
		if (blocklist == null) {
			throw new IllegalArgumentException(
					"BlocklistQuery is required — safety must not run without a blocklist (ADR-0002)");
		}
		this.blocklist = blocklist;
	}

	/**
	 * 본문과 선택지를 함께 판정한다 (§9.1 의 L2 행 — {@code paragraphs} + {@code choices}).
	 *
	 * <p>둘을 나눠 부르지 않는 이유는 <b>한쪽만 검사하는 호출이 생기지 않게</b> 하기 위해서다.
	 * 선택지 텍스트도 사용자에게 도달하는 문자열이다.
	 */
	public SafetyJudgement judge(List<String> paragraphs, List<String> choices) {
		List<BlocklistEntry> entries;
		try {
			entries = this.blocklist.findAll();
		}
		catch (RuntimeException ex) {
			// fail-closed. 원인은 남기되 판정 대상 원문은 남기지 않는다 (S-3).
			log.error("blocklist lookup failed — blocking as a closed failure (ADR-0002)", ex);
			return new SafetyJudgement(SafetyOutcome.BLOCK, Set.of());
		}

		if (entries == null) {
			log.error("blocklist lookup returned null — blocking as a closed failure (ADR-0002)");
			return new SafetyJudgement(SafetyOutcome.BLOCK, Set.of());
		}

		Set<SafetyCategory> hits = hitsIn(matchTarget(paragraphs, choices), entries);
		if (hits.isEmpty()) {
			return SafetyJudgement.pass();
		}

		SafetyOutcome outcome = CategoryPolicy.decide(hits);
		if (outcome != SafetyOutcome.MASKED) {
			return new SafetyJudgement(outcome, hits);
		}

		// §9.2 — 마스킹 후 통과. 단, **가릴 수 있을 때만**이다.
		MaskedText masked = PersonalDataMasker.mask(paragraphs, choices, entries);
		if (masked == null) {
			// 자리를 모른다. 원문을 임의로 잘라내는 대신 결과를 폐기하고 다시 만든다 —
			// 재생성 후에도 걸리면 차단이다 (fail-closed).
			return new SafetyJudgement(SafetyOutcome.REGENERATE, hits);
		}
		return new SafetyJudgement(SafetyOutcome.MASKED, hits, masked);
	}

	/**
	 * 대조 대상 문자열 — <b>받은 것을 전부 이어 붙인다</b> (#84).
	 *
	 * <p>문단마다 따로 대조하면 <b>문단 경계에 걸친 표현이 빠져나간다.</b> 탐지는 언제나 이
	 * 하나의 규칙으로 하고, 마스킹의 검증도 같은 규칙을 쓴다 — 규칙이 둘이 되면 한쪽만 관대해진다.
	 */
	static String matchTarget(List<String> paragraphs, List<String> choices) {
		return normalizeAll(paragraphs) + normalizeAll(choices);
	}

	/** 정규화된 대조 대상에서 걸린 카테고리들 (R2.5 — 정규화 값끼리 비교한다). */
	static Set<SafetyCategory> hitsIn(String target, List<BlocklistEntry> entries) {
		Set<SafetyCategory> hits = new LinkedHashSet<>();
		for (BlocklistEntry entry : entries) {
			if (target.contains(entry.normalizedValue())) {
				hits.add(entry.category());
			}
		}
		return hits;
	}

	private static String normalizeAll(List<String> texts) {
		if (texts == null) {
			return "";
		}
		StringBuilder joined = new StringBuilder();
		texts.forEach(text -> joined.append(TextNormalizer.normalize(text)));
		return joined.toString();
	}
}
