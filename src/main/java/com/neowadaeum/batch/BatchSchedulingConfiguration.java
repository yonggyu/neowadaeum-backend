package com.neowadaeum.batch;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치 스케줄링을 켠다 (B-39, ADR-0003).
 *
 * <p><b>{@code batch} 모듈 안에 둔다.</b> "언제 실행하는가"는 이 모듈의 책임이며, 애플리케이션
 * 전역 설정으로 올리면 다른 모듈이 {@code @Scheduled} 를 붙이는 것을 막을 근거가 사라진다 —
 * 그러면 데이터 소유 모듈이 자기 배치를 갖게 되고 이 경계가 무너진다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class BatchSchedulingConfiguration {

}
