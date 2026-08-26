package com.neowadaeum.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * #25 — Provider 시간 제한이 <b>설정에서 온다.</b>
 *
 * <p>이 이슈가 요구한 것은 "25 라는 숫자"의 재확인이 아니라 <b>설정된 값이 바인딩되어 쓰이는가</b>
 * 다. 그래야 §10.1-9(타임아웃 초과 시 세션 상태 불변)가 실제로 25초를 기다리지 않고 같은 코드
 * 경로를 검증할 수 있다.
 *
 * <p><b>이 값의 의미는 "한 번의 호출"이 아니라 "재요청을 포함한 생성 전체"다</b> (§13-19, B-21-2).
 * 그것을 지키는지는 여기서 볼 수 없다 — 바인딩만으로는 예산이 어디까지 걸리는지 알 수 없기
 * 때문이다. {@code SchemaRetryingStoryProviderTests} 의 {@code S13_19_*} 가 그 자리다.
 */
class ProviderPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(EnableProperties.class);

	/** 설정한 값이 그대로 바인딩된다. 테스트가 짧은 값을 주입하는 경로가 이것이다. */
	@Test
	void R6_4_the_configured_value_is_bound() {
		this.runner.withPropertyValues("ai.provider.timeout-ms=250")
				.run(context -> assertThat(context.getBean(ProviderProperties.class).timeoutMs())
						.isEqualTo(Duration.ofMillis(250)));
	}

	/**
	 * <b>값이 없으면 계약값이다</b> (§4.3, §11).
	 *
	 * <p>§7.3 이 부팅을 멈추라고 하는 대상은 접속 정보와 시크릿처럼 환경마다 다른 값이다. 여기서
	 * 올바른 운영 값은 하나뿐이고 코드가 그것을 안다 — 빠졌다고 뜨지 않는 편이 더 나쁘다.
	 */
	@Test
	void S6_3_a_missing_value_falls_back_to_the_documented_contract() {
		this.runner.run(context -> assertThat(context.getBean(ProviderProperties.class).timeoutMs())
				.isEqualTo(Duration.ofSeconds(25)));
	}

	/** {@code -ms} 는 설정에 적는 숫자의 단위다 — {@code 25000} 은 25초이지 25000초가 아니다. */
	@Test
	void R6_4_the_number_is_read_as_milliseconds() {
		this.runner.withPropertyValues("ai.provider.timeout-ms=25000")
				.run(context -> assertThat(context.getBean(ProviderProperties.class).timeoutMs())
						.isEqualTo(Duration.ofSeconds(25)));
	}

	/** R3.1 — 활성 Provider 도 설정에서 온다 (B-18). */
	@Test
	void R3_1_the_active_provider_is_bound() {
		this.runner.withPropertyValues("ai.provider.active=anthropic")
				.run(context -> assertThat(context.getBean(ProviderProperties.class).active())
						.isEqualTo("anthropic"));
	}

	/**
	 * <b>비워 둔 값과 적지 않은 값을 같게 본다.</b>
	 *
	 * <p>yml 에 {@code active:} 만 적고 값을 비우는 일이 잦다. 그것을 "이름이 빈 Provider" 로 읽으면
	 * 오류 메시지가 등록 목록이 아니라 엉뚱한 곳을 가리킨다.
	 */
	@Test
	void R3_1_a_blank_active_is_the_same_as_unset() {
		this.runner.withPropertyValues("ai.provider.active=")
				.run(context -> assertThat(context.getBean(ProviderProperties.class).active()).isNull());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ProviderProperties.class)
	static class EnableProperties {
	}
}
