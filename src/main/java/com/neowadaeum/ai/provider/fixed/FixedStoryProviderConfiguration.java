package com.neowadaeum.ai.provider.fixed;

import tools.jackson.databind.ObjectMapper;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TimeLimitedStoryProvider;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code FixedStoryProvider} 등록 경계 (S-3).
 *
 * <p><b>{@code prod} 에서는 이 구성이 통째로 비활성이다</b> (R3.1, I-14, ADR-0004 "대체 수단의 안전 조건" 2).
 * 결정론 Provider 가 운영 등록 경로에 섞이면 실제 사용자에게 고정 응답이 나갈 수 있고, 그 사고는
 * 조용하다 — 에러가 아니라 "이상한 이야기"로 나타난다.
 *
 * <p>{@code @Profile("!prod")} 를 쓴 것은 <b>기본값이 비활성이 아니라 차단이 명시적</b>이어야 하기
 * 때문이다. 새 프로파일이 생겨도 {@code prod} 만 아니면 개발·테스트 편의는 유지되고, {@code prod} 는
 * 프로파일 이름 하나로 확실히 닫힌다.
 *
 * <p>이 보장은 애노테이션이 붙어 있다는 확인이 아니라 <b>동작</b>으로 검증한다 —
 * {@code FixedStoryProviderRegistrationTests}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!prod")
public class FixedStoryProviderConfiguration {

	@Bean
	public FixedStoryScenarioLoader fixedStoryScenarioLoader(ObjectMapper objectMapper) {
		return new FixedStoryScenarioLoader(objectMapper);
	}

	@Bean
	public FixedStoryProvider fixedStoryProvider(FixedStoryScenarioLoader loader) {
		return new FixedStoryProvider(loader.load());
	}

	/**
	 * 호출자에게 주입되는 Provider 는 <b>시간 제한으로 감싼 것</b>이다 (R6.4, §6.3).
	 *
	 * <p>어댑터마다 스스로 지키게 하면 새 어댑터가 그것을 잊는다. 감싸는 쪽에 두면 잊을 자리가 없다 —
	 * provider 와 무관하게 서버가 보장한다는 점에서 I-13 과 같은 성질이다.
	 *
	 * <p>실행기를 가상 스레드로 둔다. 대기가 대부분인 호출이라 플랫폼 스레드를 붙들 이유가 없다.
	 */
	@Bean
	@Primary
	public StoryProvider timeLimitedStoryProvider(FixedStoryProvider delegate) {
		return new TimeLimitedStoryProvider(delegate, Executors.newVirtualThreadPerTaskExecutor());
	}
}
