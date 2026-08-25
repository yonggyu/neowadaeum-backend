package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/**
 * S-10 (B-47, #70) — <b>dev 콘솔이 열려서는 안 되는 곳에서 열리지 않는다.</b>
 *
 * <p>B-47 의 DoD 는 "{@code prod} 프로파일에서 404" 다. 매핑은 빈에서 나오므로 <b>빈이 없으면
 * 404 다</b> — {@code DevPlayerRefBypassTests}(#34) 와 같은 기준으로, 애노테이션 확인이 아니라
 * 컨텍스트를 실제로 띄워 빈 부재를 본다.
 */
class DevPlayConsoleProfileTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(DevPlayConsoleController.class);

	/**
	 * <b>프로파일을 하나도 지정하지 않으면 콘솔이 없다.</b>
	 *
	 * <p>{@code @Profile("!prod")} 를 썼다면 여기서 열린다 — 프로파일 지정을 빠뜨린 배포는 실수로
	 * 만들어진다. 같은 함정을 {@code FixedStoryProvider}(#47) 와 우회 리졸버(#34)에서 확인했다.
	 */
	@Test
	void B47_no_active_profile_means_no_console() {
		this.runner.run(context -> assertThat(context).doesNotHaveBean(DevPlayConsoleController.class));
	}

	/** {@code prod} 에서 콘솔이 없다 — 매핑이 없으므로 404 다 (B-47 DoD). */
	@Test
	void B47_prod_has_no_console() {
		this.runner.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).doesNotHaveBean(DevPlayConsoleController.class));
	}

	/** {@code dev} 와 {@code prod} 가 함께 켜져도 콘솔이 없다. {@code prod} 가 이긴다. */
	@Test
	void B47_prod_wins_even_when_dev_is_also_active() {
		this.runner.withPropertyValues("spring.profiles.active=prod,dev")
				.run(context -> assertThat(context).doesNotHaveBean(DevPlayConsoleController.class));
	}

	/** {@code dev} 에서는 존재한다. 차단이 개발까지 막으면 검증 도구의 의미가 없다. */
	@Test
	void B47_dev_provides_the_console() {
		this.runner.withPropertyValues("spring.profiles.active=dev")
				.run(context -> assertThat(context).hasSingleBean(DevPlayConsoleController.class));
	}

	/**
	 * <b>HTML 은 자동 서빙 경로 밖에 있다.</b>
	 *
	 * <p>{@code static/} · {@code public/} 아래로 옮기면 Spring Boot 가 프로파일과 무관하게 서빙해
	 * 위의 프로파일 게이트를 통째로 우회한다. 파일이 옮겨지면 여기서 잡힌다.
	 */
	@Test
	void B47_the_console_page_is_outside_auto_served_locations() {
		assertThat(new ClassPathResource("devconsole/play-console.html").exists()).isTrue();
		assertThat(new ClassPathResource("static/devconsole/play-console.html").exists()).isFalse();
		assertThat(new ClassPathResource("public/devconsole/play-console.html").exists()).isFalse();
	}
}
