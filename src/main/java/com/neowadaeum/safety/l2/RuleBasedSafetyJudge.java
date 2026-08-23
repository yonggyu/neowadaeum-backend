package com.neowadaeum.safety.l2;

import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.BlocklistQuery;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyPolicy;
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
 * 규정하며, 2단(모델 기반)은 B-30 복귀 시점이다. <b>축소된 실물이지 스텁이 아니다</b> — 규칙
 * 기반 판정기는 실제로 차단한다 (§0.2, ADR-0004).
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
		String normalized = normalizeAll(paragraphs) + normalizeAll(choices);

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

		Set<SafetyCategory> hits = new LinkedHashSet<>();
		for (BlocklistEntry entry : entries) {
			if (normalized.contains(entry.normalizedValue())) {
				hits.add(entry.category());
			}
		}

		if (hits.isEmpty()) {
			return SafetyJudgement.pass();
		}

		return new SafetyJudgement(decide(hits), hits);
	}

	/**
	 * 정책이 가장 강한 것을 따른다.
	 *
	 * <p>즉시차단이 하나라도 있으면 <b>재생성하지 않는다</b> (§9.2, B-30 DoD). 재생성 대상과 섞였을
	 * 때 약한 쪽을 따르면 즉시차단이 사실상 사라진다.
	 */
	private static SafetyOutcome decide(Set<SafetyCategory> hits) {
		if (hits.stream().anyMatch(SafetyCategory::blocksImmediately)) {
			return SafetyOutcome.BLOCK;
		}

		if (hits.stream().anyMatch(category -> category.policy() == SafetyPolicy.MASK)) {
			// §9.2 는 마스킹 후 통과를 규정하지만 규칙 기반 대조는 위치(span)를 모른다.
			// 스텁으로 통과시키지 않는다 (§0.2).
			throw new UnsupportedOperationException(
					"masking policy is not implemented — see §9.2 and B-30");
		}

		return SafetyOutcome.REGENERATE;
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
