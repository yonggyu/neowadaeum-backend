package com.neowadaeum.ai.log;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 호출 기록 배선 (B-25).
 *
 * <p><b>가상 스레드 실행기를 쓴다.</b> 기록은 DB 쓰기 한 번이라 대기가 대부분이며, 플랫폼
 * 스레드를 붙들 이유가 없다. {@code AiGatewayConfiguration} 이 Provider 호출에 같은 선택을 했다.
 *
 * <p><b>실행기를 공유하지 않는다.</b> Provider 호출과 기록이 같은 풀을 쓰면, 기록이 밀릴 때
 * <b>생성이 함께 밀린다</b> — 관측이 서비스를 붙잡는 형태다.
 *
 * <p><b>계측이 기록을 감싼다</b> (B-48). 모든 호출이 이미 기록기를 지나므로 새 계측 지점을
 * 만들지 않는다 — 만들면 둘 중 하나만 지나는 경로가 생긴다.
 */
@Configuration(proxyBeanMethods = false)
public class AiCallLogConfiguration {

	@Bean
	public AiCallRecorder aiCallRecorder(AiCallLogRepository repository, Clock clock,
			MeterRegistry meterRegistry) {
		return new MeteredAiCallRecorder(
				new AsyncAiCallRecorder(repository, Executors.newVirtualThreadPerTaskExecutor(), clock),
				meterRegistry);
	}
}
