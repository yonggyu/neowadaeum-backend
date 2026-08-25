package com.neowadaeum.ai.provider;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * Provider 호출 공통 설정 (#25 — B-18 · B-22 선행 정리).
 *
 * <p><b>어느 Provider 를 붙이든 같아야 하는 값만 둔다.</b> 시간 제한은
 * {@link TimeLimitedStoryProvider} 가 감싸서 강제하므로 어댑터마다 다를 수 없다 — provider 와
 * 무관하게 서버가 보장한다는 점에서 I-13 과 같은 성질이다. 어댑터별 키·엔드포인트는
 * {@code ai.providers.*} 이고 이것과 별개다.
 *
 * <p><b>설정으로 뺀 이유는 테스트다.</b> 25초가 코드 상수로 박혀 있으면 §10.1-9(타임아웃 초과 시
 * 세션 상태 불변)를 검증하는 테스트가 <b>실제로 25초를 기다린다.</b> ADR-0001 이 실측한 이
 * 프로젝트의 통합 테스트 고정 비용은 컨텍스트 기동 6초 남짓이고, 거기에 25초짜리가 하나 붙으면
 * 그 테스트 하나가 PR 파이프라인을 지배한다. 테스트는 짧은 값을 주입해 <b>같은 코드 경로</b>를
 * 지난다 (§7.2 — 테스트 yml 을 만들지 않는다).
 *
 * @param timeoutMs Provider 한 번 호출의 상한 (R6.4, §6.3). 이름의 {@code -ms} 는 <b>설정에 적는
 *                  숫자의 단위</b>를 가리킨다 — 타입은 {@link Duration} 이다
 */
@ConfigurationProperties("ai.provider")
public record ProviderProperties(@DurationUnit(ChronoUnit.MILLIS) Duration timeoutMs) {

	/**
	 * §4.3 · §11 이 못박은 계약값이다. <b>배포마다 정하는 값이 아니다.</b>
	 *
	 * <p>그래서 값이 없을 때 부팅을 실패시키지 않는다 — §7.3 이 부팅을 멈추라고 하는 대상은 접속
	 * 정보와 시크릿처럼 <b>환경마다 다르고 틀리면 조용히 잘못 도는</b> 값이다. 여기서 올바른 운영
	 * 값은 하나뿐이고, 그것을 코드가 알고 있다.
	 */
	public static final Duration CONTRACT_TIMEOUT = Duration.ofSeconds(25);

	public ProviderProperties {
		if (timeoutMs == null) {
			timeoutMs = CONTRACT_TIMEOUT;
		}
	}
}
