package com.neowadaeum.play.orchestrator;

import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 요약 파이프라인 배선 (B-34).
 *
 * <p><b>가상 스레드다.</b> 요약은 대기가 대부분인 외부 호출이라 플랫폼 스레드를 붙들 이유가 없다 —
 * {@code AsyncAiCallRecorder}(B-25)와 같은 판단이다.
 *
 * <p><b>큐 상한을 따로 두지 않는다.</b> 요약 호출의 동시 실행 수를 제한해야 하는 시점은 부하 실측
 * (B-46) 이후이며, 지금 임의의 숫자를 두면 <b>근거 없는 값이 상한처럼 굳는다.</b> 폭주 방지는
 * 요약이 <b>턴당 최대 한 번</b>이고 같은 구간을 두 번 부르지 않는다는 성질이 먼저 맡는다.
 */
@Configuration(proxyBeanMethods = false)
public class SummaryPipelineConfiguration {

	@Bean
	public AsyncSummaryTrigger asyncSummaryTrigger(StorySummarizer summarizer) {
		return new AsyncSummaryTrigger(summarizer, Executors.newVirtualThreadPerTaskExecutor());
	}
}
