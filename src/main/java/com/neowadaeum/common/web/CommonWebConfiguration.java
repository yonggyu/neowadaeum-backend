package com.neowadaeum.common.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * common 모듈이 소유하는 웹 인프라 등록 지점.
 */
@Configuration(proxyBeanMethods = false)
public class CommonWebConfiguration {

	/**
	 * {@link RequestIdFilter} 를 필터 체인 최선두에 둔다.
	 *
	 * <p>Security 필터보다 앞서야 인증 실패 응답까지 같은 추적 ID 를 갖는다.
	 */
	@Bean
	public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
		FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}
}
