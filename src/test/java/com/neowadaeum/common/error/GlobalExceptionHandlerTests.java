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
import java.util.List;
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
	void SEC6_unhandled_exception_does_not_leak_internals() throws Exception {
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
	void SEC3_rejected_value_is_not_echoed_back() throws Exception {
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

	/**
	 * #466 — 본문이 모델로 풀리지 못한 실패도 어느 자리가 어긋났는지 알린다.
	 *
	 * <p>{@code @Valid} 이전 단계라 예전에는 {@code details} 가 빈 객체로 나갔고, 클라이언트는 무엇을
	 * 고쳐야 하는지 알 수 없었다.
	 */
	@Test
	void S9_1_unreadable_body_reports_the_offending_field_path() throws Exception {
		mockMvc.perform(post("/probe/typed")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"turnNo\":\"열두번째\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.details.fields").isArray())
				.andExpect(jsonPath("$.details.fields[0].field").value("turnNo"))
				.andExpect(jsonPath("$.details.fields[0].reason").isNotEmpty());
	}

	/** #466 — 중첩 구조에서도 자리를 특정한다. 목록 안의 원소는 인덱스까지 붙는다. */
	@Test
	void S9_1_unreadable_body_reports_a_nested_field_path() throws Exception {
		mockMvc.perform(post("/probe/nested")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"scenes\":[{\"order\":\"첫번째\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fields[0].field").value("scenes[0].order"));
	}

	/**
	 * S-3 / S-6 — 역직렬화 실패 응답이 <b>보낸 값·내부 클래스 경로·기대 타입</b>을 되돌려 보내지 않는다.
	 *
	 * <p>Jackson 의 예외 메시지에는 이 셋이 함께 들어 있다. 자리(필드 경로)까지만 싣는 것이 이 검사가
	 * 지키는 선이다 — "있어야 할 것"만 단언하면 값이 새어도 통과한다.
	 */
	@Test
	void SEC3_unreadable_body_response_leaks_neither_value_nor_internals() throws Exception {
		String secret = "user-typed-secret-0123456789";

		String body = bodyOf(mockMvc.perform(post("/probe/typed")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"turnNo\":\"" + secret + "\"}")).andReturn());

		assertThat(body)
				.doesNotContain(secret)
				.doesNotContain("com.neowadaeum")
				.doesNotContain("ProbeTypedRequest")
				.doesNotContain("java.lang")
				.doesNotContain("Integer")
				.doesNotContain("Exception")
				.doesNotContain("tools.jackson");
	}

	/**
	 * #466 / §9.3 — 어긋난 자리를 특정할 수 없는 실패는 {@code details} 를 빈 객체로 둔다.
	 *
	 * <p>본문이 애초에 JSON 이 아니면 Jackson 이 기록한 참조 경로가 없다. 없는 자리를 지어내지 않는다.
	 */
	@Test
	void S9_3_unreadable_body_without_a_known_path_keeps_details_empty() throws Exception {
		mockMvc.perform(post("/probe/typed")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ this is not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.details").isMap())
				.andExpect(jsonPath("$.details").isEmpty());
	}

	private static String bodyOf(MvcResult result) throws Exception {
		result.getResponse().setCharacterEncoding(StandardCharsets.UTF_8.name());
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	record ProbeRequest(@NotBlank @Size(max = 5) String title) {
	}

	record ProbeTypedRequest(Integer turnNo) {
	}

	record ProbeScene(int order) {
	}

	record ProbeNestedRequest(List<ProbeScene> scenes) {
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

		@PostMapping("/typed")
		void typed(@RequestBody ProbeTypedRequest request) {
		}

		@PostMapping("/nested")
		void nested(@RequestBody ProbeNestedRequest request) {
		}
	}
}
