package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #261 — 약관 메타 (R10.2).
 *
 * <p>확인하는 것 넷 — <b>토큰 없이 열린다</b>, <b>판본이 설정에서 온다</b>,
 * <b>설정이 없으면 지어내지 않는다</b>, <b>회원 정보가 섞이지 않는다</b>.
 */
class ConsentTermsApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Instant SEEDED_AT = Instant.parse("2026-08-27T00:00:00Z");

	private static final String TERMS = """
			{"tos":     {"version":"v1.2","documentUrl":"https://example.invalid/terms/tos"},
			 "privacy": {"version":"v1.0","documentUrl":"https://example.invalid/terms/privacy"}}
			""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ServiceConfigRepository configs;

	@BeforeEach
	void configureTerms() {
		this.configs.save(ServiceConfig.of("consent.terms", TERMS, SEEDED_AT));
		this.configs.save(ServiceConfig.of("ai.notice",
				"{\"version\":\"2026-07-21\",\"text\":\"이 이야기는 AI가 생성합니다.\"}", SEEDED_AT));
	}

	@AfterEach
	void clear() {
		// 이 클래스가 심은 키만 지운다 — 표 전체를 비우면 다른 테스트가 원인 없이 깨진다 (#272).
		this.configs.deleteById("consent.terms");
		this.configs.deleteById("ai.notice");
	}

	/** <b>인증 없이 열린다</b> — 가입 전에 불리는 화면이다 (계약의 {@code security: []}). */
	@Test
	void R10_2_the_consent_terms_are_reachable_without_a_token() throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/consents")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}

	/** <b>R10.2 — 판본이 설정에서 온다.</b> 코드에도 프론트에도 없다 (#261). */
	@Test
	void R10_2_the_versions_come_from_configuration() throws Exception {
		assertThat(termOf("tos").path("version").asString()).isEqualTo("v1.2");
		assertThat(termOf("tos").path("documentUrl").asString())
				.isEqualTo("https://example.invalid/terms/tos");
		assertThat(termOf("ai_notice").path("version").asString()).isEqualTo("2026-07-21");
	}

	/** 설정을 바꾸면 응답이 바뀐다 — 그것이 "배포 없이 개정"의 실질이다 (#261). */
	@Test
	void R10_2_changing_the_configuration_changes_the_version() throws Exception {
		ServiceConfig config = this.configs.findById("consent.terms").orElseThrow();
		config.update("{\"tos\": {\"version\":\"v2.0\"}, \"privacy\": {\"version\":\"v1.0\"}}",
				SEEDED_AT.plusSeconds(3600));
		this.configs.saveAndFlush(config);

		assertThat(termOf("tos").path("version").asString()).isEqualTo("v2.0");
	}

	/** 본문 주소가 없는 종류도 <b>키를 생략하지 않는다</b> (web-api 규칙). */
	@Test
	void R10_2_a_term_without_a_document_url_keeps_the_key_as_null() throws Exception {
		assertThat(termOf("ai_notice").has("documentUrl")).isTrue();
		assertThat(termOf("ai_notice").path("documentUrl").isNull()).isTrue();
	}

	/** <b>§13-24 — {@code age} 판본은 서버가 정한다.</b> 사용자가 체크하는 항목이 아니다. */
	@Test
	void R10_2_the_age_term_is_decided_by_the_server() throws Exception {
		assertThat(termOf("age").path("version").asString()).isNotBlank();
		assertThat(termOf("age").path("required").asBoolean()).isFalse();
	}

	/**
	 * <b>설정이 없으면 판본을 지어내지 않는다</b> (#261, R10.2).
	 *
	 * <p>기본값을 주면 <b>아무도 검증하지 않는 상수 판본이 동의 이력에 남는다</b> — 이슈가
	 * 고치려던 상태가 서버로 옮겨올 뿐이다.
	 */
	@Test
	void R10_2_an_unconfigured_term_fails_instead_of_inventing_a_version() throws Exception {
		this.configs.deleteById("consent.terms");
		this.configs.deleteById("ai.notice");

		MvcResult result = this.mockMvc.perform(get("/api/v1/consents")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(500);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("INTERNAL_ERROR");
	}

	/**
	 * <b>어긋난 설정도 같은 자리로 떨어진다</b> (§13-51, 이슈 #279).
	 *
	 * <p>서버는 이 값의 형식을 검증하지 않는다. 그러면 <b>"모양이 조금 다른 값"이 통과해 이상한
	 * 판본이 동의 이력에 남는가</b>가 남는 질문이고, 답이 "아니다"라는 것을 여기서 고정한다 —
	 * JSON 이 아니든, 최상위가 객체가 아니든, 종류나 판본이 빠졌든 결과는 500 하나다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"\"이건 객체가 아니다\"",
			"[]",
			"{}",
			"{\"tos\": {\"version\": \"v1.0\"}}",
			"{\"tos\": {\"documentUrl\": \"https://example.invalid/terms/tos\"}, \"privacy\": {}}",
	})
	void S13_51_a_malformed_configuration_still_fails_as_internal_error(String raw) throws Exception {
		ServiceConfig config = this.configs.findById("consent.terms").orElseThrow();
		config.update(raw, SEEDED_AT.plusSeconds(3600));
		this.configs.saveAndFlush(config);

		MvcResult result = this.mockMvc.perform(get("/api/v1/consents")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(500);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("error").asString())
				.isEqualTo("INTERNAL_ERROR");
	}

	/**
	 * <b>S-6 — 설정이 깨져도 응답이 내부를 말하지 않는다.</b>
	 *
	 * <p>스택트레이스 · SQL · 패키지 경로 · 넣은 값 자체가 본문에 실리면 안 된다. "있어야 할
	 * 것"만 단언하면 새어도 통과하므로 {@code doesNotContain} 을 함께 건다.
	 */
	@Test
	void SEC6_a_broken_configuration_leaks_nothing_into_the_response() throws Exception {
		ServiceConfig config = this.configs.findById("consent.terms").orElseThrow();
		config.update("\"이건 객체가 아니다\"", SEEDED_AT.plusSeconds(3600));
		this.configs.saveAndFlush(config);

		String body = this.mockMvc.perform(get("/api/v1/consents")).andReturn().getResponse()
				.getContentAsString();

		assertThat(body).doesNotContain("com.neowadaeum").doesNotContain("Exception")
				.doesNotContain("select").doesNotContain("service_config")
				.doesNotContain("이건 객체가 아니다");
	}

	/**
	 * <b>인증 없이 열리는 응답에 회원에 관한 값이 하나도 없다</b> (S-9, I-3).
	 *
	 * <p>"있어야 할 것"만 단언하면 값이 새어도 통과한다 — {@code doesNotContain} 을 함께 건다.
	 */
	@Test
	void SEC9_the_response_carries_nothing_about_any_member() throws Exception {
		String body = this.mockMvc.perform(get("/api/v1/consents")).andReturn().getResponse()
				.getContentAsString();

		assertThat(body).doesNotContain("playerRef").doesNotContain("email").doesNotContain("birthDate")
				.doesNotContain("agreed").doesNotContain(TEST_PLAYER_REF.toString());
	}

	private JsonNode termOf(String consentType) throws Exception {
		MvcResult result = this.mockMvc.perform(get("/api/v1/consents")).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString()).path("terms").valueStream()
				.filter(term -> term.path("consentType").asString().equals(consentType))
				.findFirst().orElseThrow();
	}
}
