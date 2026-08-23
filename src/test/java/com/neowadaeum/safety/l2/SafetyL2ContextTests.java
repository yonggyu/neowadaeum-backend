package com.neowadaeum.safety.l2;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.spi.BlocklistQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S-8 (#61) — <b>실제 애플리케이션 컨텍스트</b>에 판정기와 SPI 구현이 있다.
 *
 * <p>{@code ApplicationContextRunner} 는 내가 넘긴 구성만 본다. 컴포넌트 스캔이 실제로 구현을
 * 집어 오는지는 진짜 컨텍스트에서만 드러나고, 그것이 되지 않으면 <b>운영에서 부팅이 실패한다.</b>
 */
class SafetyL2ContextTests extends ContainerTestBase {

	@Autowired
	private RuleBasedSafetyJudge judge;

	@Autowired
	private BlocklistQuery blocklistQuery;

	/** 판정기와 SPI 구현이 모두 배선돼 있다. 부팅했다는 사실 자체가 ADR-0002 fail-fast 의 통과다. */
	@Test
	void ADR0002_judge_and_blocklist_implementation_are_wired_in_the_real_context() {
		assertThat(this.judge).isNotNull();
		assertThat(this.blocklistQuery).isNotNull();
		assertThat(this.blocklistQuery.findAll()).as("슬라이스 기본값은 빈 블록리스트다").isEmpty();
	}
}
