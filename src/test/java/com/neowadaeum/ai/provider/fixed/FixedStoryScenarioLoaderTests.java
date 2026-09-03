package com.neowadaeum.ai.provider.fixed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.json.JsonMapper;

/** 시나리오 적재 실패를 조용히 넘기지 않는다 (S-3). */
class FixedStoryScenarioLoaderTests {

	private static FixedStoryScenarioLoader loader(String pattern) {
		return new FixedStoryScenarioLoader(JsonMapper.builder().build(),
				new PathMatchingResourcePatternResolver(), pattern);
	}

	/** 규약대로 {@code scenarios/*.json} 을 읽는다 (`.claude/rules/testing.md`). */
	@Test
	void S3_loads_scenarios_from_the_conventional_classpath_location() {
		List<FixedStoryScenario> scenarios = loader(FixedStoryScenarioLoader.DEFAULT_LOCATION_PATTERN).load();

		assertThat(scenarios).isNotEmpty();
		assertThat(scenarios).allSatisfy(scenario -> assertThat(scenario.entries()).isNotEmpty());
	}

	/** 시나리오가 없는 결정론 Provider 는 "조용히 아무것도 못 하는" Provider 다. 부팅에서 끊는다. */
	@Test
	void S3_missing_scenario_fails_loudly_instead_of_yielding_an_empty_provider() {
		assertThatThrownBy(() -> loader("classpath*:scenarios/does-not-exist-*.json").load())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no scenario found");
	}

	/** S-3 · S-11 — 실패 메시지에 파일명만 남기고 본문·경로를 흘리지 않는다. */
	@Test
	void SEC3_invalid_scenario_reports_the_file_name_without_leaking_its_content() {
		assertThatThrownBy(() -> loader("classpath*:scenarios/broken/*.json").load())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("s3-malformed.json")
				.hasMessageNotContaining("절대 로그에 남으면 안 되는 본문");
	}
}
