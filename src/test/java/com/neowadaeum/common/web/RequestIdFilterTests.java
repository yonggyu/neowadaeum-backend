package com.neowadaeum.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * §9.4 / B-03 — 요청 단위 추적 ID.
 *
 * <p>MDC 는 스레드에 매달린 전역 상태다. 심는 것만큼 <b>지우는 것</b>이 중요하다. 스레드 풀에서 재사용되는
 * 스레드에 이전 요청의 ID 가 남으면 로그가 엉뚱한 요청에 붙는다.
 */
class RequestIdFilterTests {

	private final RequestIdFilter filter = new RequestIdFilter();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void B03_request_id_is_generated_exposed_in_mdc_and_returned_as_header() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInsideChain = new AtomicReference<>();

		filter.doFilter(request, response, (req, res) -> seenInsideChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

		assertThat(seenInsideChain.get()).isNotBlank();
		assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(seenInsideChain.get());
	}

	@Test
	void B03_mdc_is_cleared_after_the_request_completes() throws Exception {
		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
		});

		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void B03_mdc_is_cleared_even_when_the_chain_fails() {
		FilterChain failing = (req, res) -> {
			throw new IllegalStateException("downstream failure");
		};

		assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
				.isInstanceOf(IllegalStateException.class);

		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void B03_well_formed_client_request_id_is_reused() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(RequestIdFilter.HEADER_NAME, "0f8c2a91-4d6e-4c11-9a77-2b0d5c1e33aa");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
		});

		assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
				.isEqualTo("0f8c2a91-4d6e-4c11-9a77-2b0d5c1e33aa");
	}

	/**
	 * 클라이언트 값이 그대로 로그 필드가 되므로 형식 검증을 통과하지 못하면 버린다.
	 *
	 * <p>개행 삽입(로그 위조)·과대 길이·빈 값이 대상이다.
	 */
	@Test
	void B03_malformed_client_request_id_is_discarded() throws Exception {
		for (String malformed : new String[] {"has space", "line\nbreak", "짧음", "x".repeat(65), ""}) {
			MockHttpServletRequest request = new MockHttpServletRequest();
			request.addHeader(RequestIdFilter.HEADER_NAME, malformed);
			MockHttpServletResponse response = new MockHttpServletResponse();

			filter.doFilter(request, response, (req, res) -> {
			});

			assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
					.as("malformed=%s", malformed)
					.isNotEqualTo(malformed)
					.matches("[0-9a-f-]{36}");
		}
	}
}
