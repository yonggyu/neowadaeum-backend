package com.neowadaeum.ai.provider.fixed;

import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
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
 * <p><b>이 구성은 어댑터를 등록만 한다</b> (B-18). 시간 제한 래퍼와 {@code @Primary} 배선은
 * {@code AiGatewayConfiguration} 으로 옮겼다 — 어댑터마다 같은 조립 코드를 복제하면 B-22 가 붙는
 * 순간 어느 쪽이 주입되는지가 애노테이션 싸움이 된다. 프로파일 경계(#47, #72)는 그대로다.
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
public class FixedStoryProviderConfiguration {

	@Bean
	public FixedStoryScenarioLoader fixedStoryScenarioLoader(ObjectMapper objectMapper) {
		return new FixedStoryScenarioLoader(objectMapper);
	}

	@Bean
	public FixedStoryProvider fixedStoryProvider(FixedStoryScenarioLoader loader) {
		return new FixedStoryProvider(loader.load());
	}
}
