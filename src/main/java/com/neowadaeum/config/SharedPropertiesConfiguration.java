package com.neowadaeum.config;

import com.neowadaeum.common.support.RecentTurnsProperties;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RetentionProperties;
import com.neowadaeum.common.support.TurnBudgetProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * <b>둘 이상의 모듈이 읽는 설정을 여기서 활성화한다</b> (#97, §5.4).
 *
 * <p>{@link RecentTurnsProperties} 는 {@code ai} 의 조립 예산과 {@code play} 의 조회 창을 <b>같은
 * 세 값</b>으로 묶는다 (§13-2). 소유가 {@code common} 이므로 활성화도 특정 모듈의 구성에 매달지
 * 않는다 — {@code ai} 쪽 구성이 조건부가 되는 날 {@code play} 가 함께 무너지면 안 된다.
 *
 * <p>설정 접두어는 {@code ai.prompt.recent-turns} 그대로다. §13-2 가 그 이름으로 부르며, 값이
 * 사는 모듈이 바뀌었다고 배포 설정 키를 바꿀 이유는 없다.
 *
 * <p>{@link TurnBudgetProperties} 도 같은 성질이다 (#116). 턴 예산을 <b>여는</b> 것은 {@code play}
 * 이고 <b>읽는</b> 것은 {@code ai} 의 시간 제한 데코레이터다 — 어느 한쪽에 두면 다른 쪽이
 * 참조할 수 없다 (ADR-0006).
 *
 * <p>{@link RateLimitProperties} 도 마찬가지다 (#157). 턴은 {@code play}, precheck 는
 * {@code authoring}(B-50), 인증 경로는 {@code identity} 가 읽는다 — <b>셋이 같은 표를 봐야 한다.</b>
 *
 * <p>{@link RetentionProperties} 는 B-61 이 더했다. 프롬프트·감사 로그는 {@code ai} 가, 세션은
 * {@code play} 가 지운다 — 그리고 <b>이 값들은 약관에 적혀 있다.</b> 한곳에 두지 않으면 고지
 * 문구와 맞춰 볼 대상이 흩어진다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ RecentTurnsProperties.class, TurnBudgetProperties.class,
		RateLimitProperties.class, RetentionProperties.class })
public class SharedPropertiesConfiguration {
}
