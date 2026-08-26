package com.neowadaeum.ai.gateway;

import com.neowadaeum.ai.provider.ProviderProperties;
import com.neowadaeum.ai.provider.SchemaRetryingStoryProvider;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TimeLimitedStoryProvider;
import java.util.List;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Provider 배선의 유일한 지점 (R3.1, B-18).
 *
 * <p><b>어댑터 설정이 배선까지 하지 않는다.</b> 이전에는 {@code FixedStoryProviderConfiguration} 이
 * {@code @Primary} 로 시간 제한 래퍼까지 만들었다. 어댑터가 하나일 때는 성립하지만, B-22 가 붙는
 * 순간 같은 조립 코드가 어댑터마다 복제되고 <b>둘 중 어느 쪽이 주입되는지가 애노테이션 싸움</b>이
 * 된다. 조립을 한 곳으로 모아 두면 새 어댑터는 자기 자신만 등록하면 된다.
 *
 * <p><b>어댑터가 하나도 없으면 부팅이 멈춘다</b> (#72 와 같은 성질). 결정론 Provider 는
 * {@code dev & !prod} 밖에서 등록되지 않으므로, 프로파일을 빠뜨린 인스턴스는 여기서 뜨지 않는다 —
 * 고정된 이야기가 조용히 나가는 것보다 낫다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
public class AiGatewayConfiguration {

	/**
	 * 호출자에게 주입되는 Provider.
	 *
	 * <p><b>{@code adapters} 에 이 빈 자신은 들어오지 않는다.</b> 스프링은 컬렉션 주입에서
	 * 자기참조를 제외한다 — 그래서 {@code StoryProvider} 를 구현한 게이트웨이가 자기 목록에 섞이지
	 * 않는다. 이 성질에 배선이 걸려 있으므로 {@code AiGatewayWiringTests} 가 그것을 못박는다.
	 *
	 * <p>실행기를 가상 스레드로 둔다. 대기가 대부분인 호출이라 플랫폼 스레드를 붙들 이유가 없다.
	 */
	/**
	 * 조립 순서 — {@code AiGateway( TimeLimited( SchemaRetrying( adapter ) ) )}.
	 *
	 * <p><b>재요청이 시간 제한 안쪽이다</b> (B-21, §13-19). §6.1 은 4단계(Provider 호출, 25s)와
	 * 5단계(파싱 → 실패 시 재요청)를 나란히 두고, §6.3 은 서버 전체 응답 예산을 28s 로 못박는다.
	 * 재요청을 제한 <b>밖</b>에 두면 두 번의 호출이 최대 50s 가 되어 그 예산을 넘는다. 안쪽에 두면
	 * 재요청을 포함한 전체가 25s 를 넘는 순간 기존대로 {@code 504 GENERATION_TIMEOUT} 이 되고,
	 * 취소도 함께 걸린다.
	 *
	 * <p><b>페이로드 검증은 가장 바깥이다.</b> 나가는 페이로드는 재요청에도 같으므로 한 번만 보면
	 * 되고, 검증이 안쪽이면 재요청 횟수만큼 같은 검사를 반복한다 (I-3, B-19).
	 */
	@Bean
	@Primary
	public StoryProvider aiGateway(List<StoryProvider> adapters, ProviderProperties properties) {
		StoryProvider selected = new ProviderRegistry(adapters).select(properties.active());
		return new AiGateway(new TimeLimitedStoryProvider(new SchemaRetryingStoryProvider(selected),
				Executors.newVirtualThreadPerTaskExecutor(), properties.timeoutMs()),
				PayloadWhitelistValidator.forProviderPayloads());
	}
}
