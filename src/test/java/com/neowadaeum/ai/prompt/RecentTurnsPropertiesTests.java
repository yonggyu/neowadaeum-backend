package com.neowadaeum.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * §13-2 — 최근 턴의 세 경계는 <b>설정</b>이다 (B-20).
 *
 * <p>§13-2 가 완충 구간(8)과 원문/압축 경계(2)를 `[결정 필요]` 로 남기고 <b>B-46 실측 후 조정</b>
 * 한다고 적었다. 코드 상수로 박아 두면 그 조정이 배포가 된다.
 */
class RecentTurnsPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(EnableProperties.class);

	/** §13-2 의 채택안이 기본값이다. */
	@Test
	void S13_2_the_adopted_boundaries_are_the_defaults() {
		this.runner.run(context -> {
			RecentTurnsProperties properties = context.getBean(RecentTurnsProperties.class);
			assertThat(properties.verbatim()).isEqualTo(2);
			assertThat(properties.inPrompt()).isEqualTo(5);
			assertThat(properties.summaryMerge()).isEqualTo(8);
		});
	}

	/** B-46 이 배포 없이 조정할 수 있어야 한다. */
	@Test
	void B46_the_boundaries_can_be_tuned_by_configuration() {
		this.runner.withPropertyValues("ai.prompt.recent-turns.verbatim=3",
						"ai.prompt.recent-turns.in-prompt=6",
						"ai.prompt.recent-turns.summary-merge=10")
				.run(context -> {
					RecentTurnsProperties properties = context.getBean(RecentTurnsProperties.class);
					assertThat(properties.verbatim()).isEqualTo(3);
					assertThat(properties.inPrompt()).isEqualTo(6);
					assertThat(properties.summaryMerge()).isEqualTo(10);
				});
	}

	/**
	 * <b>조정은 열어 두되 모순은 막는다.</b>
	 *
	 * <p>원문 구간이 프롬프트 구간보다 넓거나 완충지대가 음수가 되면 §13-2 의 구조가 무너진다.
	 */
	@Test
	void S13_2_an_inverted_ordering_is_rejected() {
		assertThatThrownBy(() -> new RecentTurnsProperties(6, 5, 8))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecentTurnsProperties(2, 9, 8))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RecentTurnsProperties.class)
	static class EnableProperties {
	}
}
