package com.neowadaeum.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §9.1 — 어떤 예외가 나가도 응답은 {@code {error, message, details}} 하나로 수렴한다.
 *
 * <p>컨텍스트를 띄우지 않는 standalone 구성이다. 여기서 검증하는 것은 예외 → 응답 변환뿐이며,
 * DataSource·Security 유무에 영향받지 않아야 한다.
 */
class GlobalExceptionHandlerTests {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	/** §11 — ApiException 은 카탈로그의 상태 코드와 코드명으로 나간다. */
	@Test
	void S11_api_exception_is_rendered_with_catalog_status_and_code() throws Exception {
		mockMvc.perform(get("/probe/turn-conflict"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("TURN_CONFLICT"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.details.turnNo").value(12));
	}

	/** §9.1 / §9.3 — details 는 값이 없어도 키가 생략되지 않고 빈 객체로 나간다. */
	@Test
	void S9_1_details_is_always_present_even_when_empty() throws Exception {
		mockMvc.perform(get("/probe/safety-blocked"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error").value("SAFETY_BLOCKED"))
				.andExpect(jsonPath("$.details").isMap())
				.andExpect(jsonPath("$.details").isEmpty());
	}

	/** R9.5 / R9.6 — 차단 응답이 재시도 유도나 차단 사유를 담지 않는다. */
	@Test
	void R9_5_safety_blocked_response_carries_no_retry_action_and_no_reason() throws Exception {
		String body = bodyOf(mockMvc.perform(get("/probe/safety-blocked")).andReturn());

		assertThat(body)
				.doesNotContain("retry")
				.doesNotContain("blocklist")
				.isEqualTo("{\"error\":\"SAFETY_BLOCKED\",\"message\":\"이 방향으로는 이야기를 이어갈 수 없어요.\",\"details\":{}}");
	}

	/** S-6 — 처리되지 않은 예외는 500 폴백으로 나가고 내부 정보를 일절 노출하지 않는다. */
	@Test
	void S6_unhandled_exception_does_not_leak_internals() throws Exception {
		MvcResult result = mockMvc.perform(get("/probe/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
				.andReturn();

		assertThat(bodyOf(result))
				.doesNotContain("IllegalStateException")
				.doesNotContain("jdbc:postgresql")
				.doesNotContain("com.neowadaeum")
				.doesNotContain("/probe/boom")
				.doesNotContain("trace");
	}

	/** §11 — Bean Validation 실패는 400 VALIDATION_ERROR 이며 어떤 필드가 틀렸는지만 알린다. */
	@Test
	void S11_bean_validation_failure_becomes_validation_error() throws Exception {
		mockMvc.perform(post("/probe/validate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.details.fields[0].field").value("title"))
				.andExpect(jsonPath("$.details.fields[0].reason").isNotEmpty());
	}

	/** S-3 / S-7 — 거절된 입력값 원문은 응답으로 되돌려 보내지 않는다. */
	@Test
	void S3_rejected_value_is_not_echoed_back() throws Exception {
		String secret = "user-typed-secret-0123456789";

		String body = bodyOf(mockMvc.perform(post("/probe/validate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"" + secret + "\"}")).andReturn());

		assertThat(body).doesNotContain(secret);
	}

	/** §9.1 — Spring MVC 가 던지는 예외도 같은 형태로 나간다. 상태 코드는 MVC 가 정한 값을 유지한다. */
	@Test
	void S9_1_spring_mvc_exceptions_share_the_same_response_shape() throws Exception {
		mockMvc.perform(get("/probe/validate"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.details").isMap());
	}

	/** §9.1 — 잘못된 JSON 본문도 동일 형태로 수렴한다. */
	@Test
	void S9_1_malformed_json_becomes_validation_error() throws Exception {
		mockMvc.perform(post("/probe/validate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ this is not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
	}

	private static String bodyOf(MvcResult result) throws Exception {
		result.getResponse().setCharacterEncoding(StandardCharsets.UTF_8.name());
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	record ProbeRequest(@NotBlank @Size(max = 5) String title) {
	}

	@RestController
	@RequestMapping("/probe")
	static class ProbeController {

		@org.springframework.web.bind.annotation.GetMapping("/turn-conflict")
		void turnConflict() {
			throw new ApiException(ErrorCode.TURN_CONFLICT, Map.of("turnNo", 12));
		}

		@org.springframework.web.bind.annotation.GetMapping("/safety-blocked")
		void safetyBlocked() {
			throw new ApiException(ErrorCode.SAFETY_BLOCKED);
		}

		@org.springframework.web.bind.annotation.GetMapping("/boom")
		void boom() {
			throw new IllegalStateException("jdbc:postgresql://localhost:5432/neowadaeum 접속 실패");
		}

		@PostMapping("/validate")
		void validate(@Valid @RequestBody ProbeRequest request) {
		}
	}
}
