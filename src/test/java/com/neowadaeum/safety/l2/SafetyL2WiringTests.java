package com.neowadaeum.safety.l2;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.common.spi.BlocklistQuery;
import com.neowadaeum.common.spi.SafetyClassifier;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * S-8 (#61) — ADR-0002 의 fail-fast 를 <b>동작으로</b> 확인한다.
 *
 * <p>"구현 빈이 없으면 부팅 실패"는 설정 파일이나 애노테이션을 확인하는 것으로 갈음할 수 없다.
 * 실제로 컨텍스트가 뜨지 않아야 한다 — 뜨는데 판정만 안 되는 상태가 가장 나쁘다.
 */
class SafetyL2WiringTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(SafetyL2Configuration.class);

	/**
	 * ADR-0002 — SPI 구현이 없으면 <b>컨텍스트가 뜨지 않는다.</b>
	 *
	 * <p>조용히 뜨는 것보다 안 뜨는 게 낫다 (§7.3 과 같은 원칙). 블록리스트 없이 뜬 서버는
	 * 검수가 없는 서버다.
	 */
	@Test
	void ADR0002_context_fails_to_start_without_a_blocklist_implementation() {
		this.runner.run(context -> assertThat(context).hasFailed());
	}

	/** 구현이 있으면 판정기가 만들어진다. 차단이 개발까지 막으면 우회가 생긴다. */
	@Test
	void ADR0002_judge_is_created_when_an_implementation_is_present() {
		this.runner.withBean(BlocklistQuery.class, () -> (BlocklistQuery) List::of)
				.withBean(SafetyClassifier.class, () -> (SafetyClassifier) request -> Set.of())
				.run(context -> {
					assertThat(context).hasSingleBean(RuleBasedSafetyJudge.class);
					assertThat(context).hasSingleBean(SafetyL2Judge.class);
				});
	}

	/**
	 * <b>B-30 — 2단 구현이 없어도 뜨지 않는다</b> (R9.2).
	 *
	 * <p>1단만으로 뜨게 두면 <b>탐지가 절반인데 아무도 모르는</b> 상태가 된다. 블록리스트가 없을
	 * 때 뜨지 않는 것과 같은 판단이다 — 조용히 약해지는 세이프티가 가장 나쁘다.
	 */
	@Test
	void B30_context_fails_to_start_without_a_classifier_implementation() {
		this.runner.withBean(BlocklistQuery.class, () -> (BlocklistQuery) List::of)
				.run(context -> assertThat(context).hasFailed());
	}
}
