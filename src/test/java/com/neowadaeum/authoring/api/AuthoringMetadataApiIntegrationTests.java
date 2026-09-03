package com.neowadaeum.authoring.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neowadaeum.ContainerTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 이슈 #282 · #315 — <b>작성 화면이 고를 값을 서버가 준다</b> (§13-56).
 *
 * <p>여기서만 확인할 수 있는 것: 장르가 <b>실제로 {@code genre} 표에서</b> 나오는지(코드 상수가
 * 아니라), 순서가 {@code display_order} 인지, 그리고 <b>토큰 없이는 아무것도 나오지 않는지</b>.
 */
class AuthoringMetadataApiIntegrationTests extends ContainerTestBase {

	private static final String PATH = "/api/v1/authoring/metadata";

	@Autowired
	private MockMvc mvc;

	/**
	 * <b>다섯 장르가 시드 순서대로 온다</b> (§13-25, #315).
	 *
	 * <p>키와 라벨이 <b>함께</b> 온다 — 키만 주면 프론트가 한국어 문구를 스스로 만들고, 그 순간
	 * 표시 문구의 정본이 프론트에 하나 더 생긴다 (#257 · #261 과 같은 종류).
	 *
	 * <p>순서를 단언하는 이유는 그것이 <b>정본이 표라는 증거</b>이기 때문이다. 코드가 다섯을
	 * 들고 있으면 {@code display_order} 를 바꿔도 응답이 그대로다.
	 */
	@Test
	void S13_25_five_genres_come_from_the_genre_table_in_display_order() throws Exception {
		this.mvc.perform(get(PATH).with(asPlayer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.genres.length()").value(5))
				.andExpect(jsonPath("$.genres[0].key").value("romance"))
				.andExpect(jsonPath("$.genres[1].key").value("school"))
				.andExpect(jsonPath("$.genres[2].key").value("fantasy"))
				.andExpect(jsonPath("$.genres[3].key").value("action"))
				.andExpect(jsonPath("$.genres[4].key").value("mystery"))
				.andExpect(jsonPath("$.genres[0].label").value("로맨스"))
				.andExpect(jsonPath("$.genres[4].label").value("미스터리"));
	}

	/**
	 * <b>네 템플릿이 파라미터 선언과 함께 온다</b> (§13-35, R7.16, #282).
	 *
	 * <p>키만 오던 것이 이 이슈의 원인이었다 — {@code affinity_at_least} 는 <b>무엇에 대한
	 * 호감도인지와 임계값</b>이 없으면 조건이 되지 않는다.
	 */
	@Test
	void S13_35_four_condition_templates_come_with_labels_and_parameters() throws Exception {
		this.mvc.perform(get(PATH).with(asPlayer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.conditionTemplates.length()").value(4))
				.andExpect(jsonPath("$.conditionTemplates[0].key").value("affinity_at_least"))
				.andExpect(jsonPath("$.conditionTemplates[1].key").value("has_flag"))
				.andExpect(jsonPath("$.conditionTemplates[2].key").value("lacks_flag"))
				.andExpect(jsonPath("$.conditionTemplates[3].key").value("turn_at_least"))
				.andExpect(jsonPath("$.conditionTemplates[0].label").value("호감도 이상"))
				.andExpect(jsonPath("$.conditionTemplates[1].parameters[0].type").value("flag"))
				.andExpect(jsonPath("$.conditionTemplates[3].parameters[0].type").value("integer"));
	}

	/**
	 * <b>{@code affinity_at_least} 는 인물과 임계값 둘을 요구한다</b> (R7.16, #282).
	 *
	 * <p>이슈가 물은 (a)/(b) 의 답이 응답에 드러나는 자리다 — <b>(b)</b> 이며, 그래서 계약이
	 * 필요한 입력을 선언한다.
	 */
	@Test
	void R7_16_affinity_at_least_declares_a_character_and_a_threshold() throws Exception {
		this.mvc.perform(get(PATH).with(asPlayer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.conditionTemplates[0].parameters.length()").value(2))
				.andExpect(jsonPath("$.conditionTemplates[0].parameters[0].type").value("character"))
				.andExpect(jsonPath("$.conditionTemplates[0].parameters[1].type").value("integer"))
				.andExpect(jsonPath("$.conditionTemplates[0].parameters[0].label").value("인물"));
	}

	/**
	 * <b>토큰이 없으면 401 이다</b> (§13-56).
	 *
	 * <p>작성자 경로이며 가입 전에 불리지 않는다 — {@code /api/v1/consents} 와 다른 자리다.
	 * 본문이 새지 않는 것도 함께 본다: 목록이 401 응답에 실리면 인증 요구가 형식이 된다.
	 */
	@Test
	void S13_56_metadata_requires_a_token() throws Exception {
		this.mvc.perform(get(PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.genres").doesNotExist())
				.andExpect(jsonPath("$.conditionTemplates").doesNotExist());
	}
}
