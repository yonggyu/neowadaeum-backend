package com.neowadaeum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * dev 자리표시로 <b>손을 대지 않고도 화면이 뜬다</b> — 그리고 <b>가짜로 보인다</b> (§13-89, 이슈 #426).
 *
 * <p>단위 테스트는 저장된 값의 모양을 본다. 여기서 보는 것은 그 값이 <b>실제 조회 경로를 통과하는가</b>다 —
 * {@code CatalogAiNoticeQuery} · {@code CatalogConsentTermsQuery} 가 정한 모양과 어긋나면 자리표시가
 * 들어가 있어도 화면은 여전히 500 이고, 그 어긋남은 JSON 한 글자에서 난다.
 *
 * <p><b>키를 지우고 시더를 직접 부른다.</b> 기동 때 심긴 행에 기대면 {@code service_config} 를 비우는
 * 다른 테스트의 실행 순서에 결과가 매인다 — 통합 테스트는 컨텍스트를 한 벌로 공유한다.
 */
class DevServiceConfigSeedIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String TERMS_KEY = "consent.terms";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ServiceConfigRepository configs;

	@Autowired
	private DevServiceConfigSeed seed;

	/** 손으로 넣은 것을 전부 지우고 — 베이스가 심은 고지까지 — 시더에게만 맡긴다. */
	@BeforeEach
	void seedLikeAFreshDevBoot() {
		this.configs.deleteById(NOTICE_KEY);
		this.configs.deleteById(TERMS_KEY);
		this.seed.run(null);
	}

	@AfterEach
	void clear() {
		// 이 클래스가 건드린 키만 지운다 — 표 전체를 비우면 다른 테스트가 원인 없이 깨진다 (#272).
		this.configs.deleteById(NOTICE_KEY);
		this.configs.deleteById(TERMS_KEY);
	}

	/** <b>{@code dev} 는 아무것도 손으로 넣지 않아도 뜬다.</b> 두 공개 경로가 그것을 보여 준다. */
	@Test
	void S13_89_the_public_screens_open_without_anyone_inserting_a_row() throws Exception {
		assertThat(status("/api/v1/landing")).isEqualTo(200);
		assertThat(status("/api/v1/consents")).isEqualTo(200);
	}

	/**
	 * <b>들어간 값은 운영 값이 아님이 값 자체로 보인다.</b>
	 *
	 * <p>응답에 그대로 실리므로 화면을 보는 사람도 안다 — 프로파일 경계가 못 잡는 자리를 사람 눈이
	 * 잡는 경로가 이것이다.
	 */
	@Test
	void S13_89_the_seeded_values_announce_themselves_as_placeholders() throws Exception {
		assertThat(body("/api/v1/landing").path("noticeText").asString()).startsWith("[개발용 자리표시");
		assertThat(body("/api/v1/consents").path("terms").valueStream()
				.filter(term -> term.path("consentType").asString().equals("tos"))
				.findFirst().orElseThrow().path("version").asString())
				.isEqualTo("dev-placeholder");
	}

	private int status(String path) throws Exception {
		return this.mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
	}

	private JsonNode body(String path) throws Exception {
		MvcResult result = this.mockMvc.perform(get(path)).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JSON.readTree(result.getResponse().getContentAsString());
	}

}
