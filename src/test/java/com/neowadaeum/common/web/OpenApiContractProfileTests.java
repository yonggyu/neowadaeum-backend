package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * B-06 — <b>계약 문서 경로가 열려서는 안 되는 곳에서 열리지 않는다.</b>
 *
 * <p>기준은 dev 콘솔(B-47, #70)·인증 우회 리졸버(#34)와 같다 — 애노테이션을 읽는 것이 아니라
 * 컨텍스트를 실제로 띄워 <b>빈 부재</b>를 본다. 매핑은 빈에서 나오므로 빈이 없으면 404 다.
 */
class OpenApiContractProfileTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(OpenApiContractController.class);

	/**
	 * <b>프로파일을 하나도 지정하지 않으면 계약 경로가 없다.</b>
	 *
	 * <p>{@code @Profile("!prod")} 를 썼다면 여기서 열린다 — 프로파일 지정을 빠뜨린 배포는 실수로
	 * 만들어진다 (#47 에서 확인한 함정).
	 */
	@Test
	void B06_no_active_profile_means_no_contract_endpoint() {
		this.runner.run(context -> assertThat(context).doesNotHaveBean(OpenApiContractController.class));
	}

	@Test
	void B06_prod_has_no_contract_endpoint() {
		this.runner.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).doesNotHaveBean(OpenApiContractController.class));
	}

	/** {@code dev} 와 {@code prod} 가 함께 켜져도 열리지 않는다. {@code prod} 가 이긴다. */
	@Test
	void B06_prod_wins_even_when_dev_is_also_active() {
		this.runner.withPropertyValues("spring.profiles.active=prod,dev")
				.run(context -> assertThat(context).doesNotHaveBean(OpenApiContractController.class));
	}

	/** {@code dev} 에서는 존재한다. 차단이 개발까지 막으면 계약을 볼 방법이 없다. */
	@Test
	void B06_dev_serves_the_contract() {
		this.runner.withPropertyValues("spring.profiles.active=dev")
				.run(context -> assertThat(context).hasSingleBean(OpenApiContractController.class));
	}
}
