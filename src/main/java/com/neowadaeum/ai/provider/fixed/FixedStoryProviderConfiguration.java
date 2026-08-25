package com.neowadaeum.ai.provider.fixed;

import tools.jackson.databind.ObjectMapper;
import com.neowadaeum.ai.provider.ProviderProperties;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TimeLimitedStoryProvider;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * <p><b>{@code @Profile("dev & !prod")} 다 — {@code "!prod"} 가 아니다</b> (#47). 초안은
 * {@code "!prod"} 였고, 그 표현식은 <b>프로파일이 하나도 활성화되지 않은 상태에서도 참</b>이라서
 * {@code spring.profiles.active} 를 지정하지 않고 뜬 인스턴스에 결정론 Provider 가 등록됐다. 차단이
 * <b>"프로파일 이름을 빠뜨리지 않는 것"에 의존</b>하는 구조였다 — 없으면 안 켜지는 쪽이 기본값이어야
 * 한다. 같은 함정을 인증 우회(#34)와 dev 콘솔(#70)에서 이미 같은 표현식으로 닫았다.
 *
 * <p><b>새 설정 프로퍼티를 만들지 않는다.</b> Provider 활성/비활성 전환은 <b>B-18</b> 의 DoD(R3.1)
 * 이고, 여기서 별도 스위치를 먼저 만들면 B-18 착수 시 스위치 둘을 합치는 일이 생긴다(#47 의 의존
 * 항목). 이 변경은 스위치 추가가 아니라 <b>기존 조건을 조인 것</b>이다.
 *
 * <p><b>꺼졌을 때 조용하지 않다.</b> 이 구성이 비활성이면 {@code StoryProvider} 빈이 없고, 그것을
 * 필수 인자로 받는 턴 파이프라인이 만들어지지 않아 <b>부팅이 멈춘다</b>. 고정된 이야기가 조용히
 * 나가는 것보다 뜨지 않는 편이 안전하다 — {@code PlayerRefResolver} 와 같은 성질이다. 실 Provider 는
 * B-22 에서 붙는다.
 *
 * <p>이 보장은 애노테이션이 붙어 있다는 확인이 아니라 <b>동작</b>으로 검증한다 —
 * {@code FixedStoryProviderRegistrationTests}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev & !prod")
@EnableConfigurationProperties(ProviderProperties.class)
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
	 *
	 * <p>제한 시간은 {@link ProviderProperties} 에서 온다 (#25). 여기에 상수를 적으면 어댑터가
	 * 늘어날 때마다 같은 숫자가 복제된다.
	 */
	@Bean
	@Primary
	public StoryProvider timeLimitedStoryProvider(FixedStoryProvider delegate, ProviderProperties properties) {
		return new TimeLimitedStoryProvider(delegate, Executors.newVirtualThreadPerTaskExecutor(),
				properties.timeoutMs());
	}
}
