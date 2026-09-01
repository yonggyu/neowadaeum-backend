package com.neowadaeum.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각 공급자 (§5.2 {@code common/support} 의 Clock).
 *
 * <p><b>도메인이 {@code Instant.now()} 를 직접 부르지 않게 하려고 둔다.</b> 안에서 현재 시각을
 * 읽으면 같은 입력이 같은 결과를 내지 않고, 테스트가 시간을 고정할 수 없다 — S-2 의 엔티티들이
 * {@code now} 를 인자로 받는 것과 같은 이유다.
 *
 * <p>UTC 로 고정한다 (§9.1). 서버 타임존이 바뀌어도 저장된 값의 의미가 변하지 않아야 한다.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
