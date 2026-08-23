package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.config.DevPlayApiSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * S-9-2 (#65, #34) — <b>인증 우회가 열려서는 안 되는 곳에서 열리지 않는다.</b>
 *
 * <p>ADR-0004 는 {@code dev} 고정 {@code player_ref} 를 *"편의가 아니라 인증 우회이며, B-47 이
 * {@code prod} 에서 404 여야 하는 것과 같은 이유이고 그보다 위험하다"* 고 규정한다. #34 는 그
 * 차단을 <b>테스트로 강제할 것</b>을 조건으로 걸었다.
 *
 * <p><b>애노테이션이 붙어 있다는 확인으로 갈음하지 않는다.</b> 컨텍스트를 실제로 띄우고 빈이
 * 없는지 본다 — PR 템플릿의 보안 항목이 요구하는 기준이다.
 */
class DevPlayerRefBypassTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(DevFixedPlayerRefResolver.class);

	/**
	 * <b>프로파일을 하나도 지정하지 않으면 우회가 없다.</b>
	 *
	 * <p>가장 중요한 경우다. {@code @Profile("!prod")} 를 썼다면 여기서 열린다 — 프로파일 지정을
	 * 빠뜨린 배포는 실수로 만들어지고, 그때 인증이 통째로 사라진다. 같은 함정을
	 * {@code FixedStoryProvider} 에서 먼저 확인했다(#47).
	 */
	@Test
	void ADR0004_no_active_profile_means_no_bypass() {
		this.runner.run(context -> assertThat(context).doesNotHaveBean(PlayerRefResolver.class));
	}

	/** {@code prod} 에서 우회가 없다 (#34). */
	@Test
	void ADR0004_prod_has_no_bypass() {
		this.runner.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).doesNotHaveBean(PlayerRefResolver.class));
	}

	/**
	 * <b>{@code dev} 와 {@code prod} 가 함께 켜져도 우회가 없다.</b>
	 *
	 * <p>{@code @Profile("dev")} 만 썼다면 여기서 열린다. 그런 조합은 실수로 만들어지며,
	 * {@code prod} 가 켜져 있으면 무슨 일이 있어도 존재해서는 안 된다.
	 */
	@Test
	void ADR0004_prod_wins_even_when_dev_is_also_active() {
		this.runner.withPropertyValues("spring.profiles.active=prod,dev")
				.run(context -> assertThat(context).doesNotHaveBean(PlayerRefResolver.class));
	}

	/**
	 * {@code dev} 에서는 존재한다.
	 *
	 * <p>차단이 개발까지 막으면 우회가 다른 형태로 생긴다 — 슬라이스는 이 빈 위에서 돈다.
	 */
	@Test
	void ADR0004_dev_provides_a_fixed_player_ref() {
		this.runner.withPropertyValues("spring.profiles.active=dev")
				.run(context -> {
					assertThat(context).hasSingleBean(PlayerRefResolver.class);
					assertThat(context.getBean(PlayerRefResolver.class).currentPlayerRef()).isNotNull();
				});
	}

	/**
	 * <b>보안 구성도 같은 조건으로 막힌다.</b>
	 *
	 * <p>우회는 두 조각이다 — 리졸버가 "누구인가"를 고정하고, {@code DevPlayApiSecurityConfiguration}
	 * 이 "들어올 수 있는가"를 연다. <b>한쪽만 막으면 다른 쪽이 남는다.</b> 여기서는 존재하지 않아야
	 * 하는 방향만 확인한다 — {@code dev} 에서 실제로 동작한다는 것은 {@code PlayApiIntegrationTests}
	 * 가 HTTP 로 증명한다.
	 */
	@Test
	void ADR0004_dev_security_configuration_is_absent_outside_dev() {
		ApplicationContextRunner securityRunner = new ApplicationContextRunner()
				.withUserConfiguration(DevPlayApiSecurityConfiguration.class);

		securityRunner.run(context ->
				assertThat(context).doesNotHaveBean(DevPlayApiSecurityConfiguration.class));
		securityRunner.withPropertyValues("spring.profiles.active=prod").run(context ->
				assertThat(context).doesNotHaveBean(DevPlayApiSecurityConfiguration.class));
		securityRunner.withPropertyValues("spring.profiles.active=prod,dev").run(context ->
				assertThat(context).doesNotHaveBean(DevPlayApiSecurityConfiguration.class));
	}

	/** 고정 값이다. 요청마다 달라지면 세션 소유자 판정이 무너진다. */
	@Test
	void ADR0004_the_dev_player_ref_is_stable_across_calls() {
		PlayerRefResolver resolver = new DevFixedPlayerRefResolver();

		assertThat(resolver.currentPlayerRef()).isEqualTo(resolver.currentPlayerRef());
	}
}
