package com.neowadaeum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.spi.ConsentTerm;
import com.neowadaeum.common.spi.ServiceConfigQuery;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #261 — 약관 설정값의 모양을 아는 유일한 곳 (R10.2).
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class CatalogConsentTermsQueryTests {

	private static final String TERMS = """
			{"tos":     {"version":"1.2","documentUrl":"/terms/tos"},
			 "privacy": {"version":"1.0"}}
			""";

	private final ServiceConfigQuery configs = mock(ServiceConfigQuery.class);

	private final AiNoticeQuery notices = mock(AiNoticeQuery.class);

	private final CatalogConsentTermsQuery query = new CatalogConsentTermsQuery(this.configs, this.notices);

	/** R10.2 — 판본과 본문 주소가 설정에서 온다. */
	@Test
	void R10_2_a_configured_term_is_parsed() {
		givenConfig(TERMS);

		assertThat(this.query.find("tos")).contains(new ConsentTerm("1.2", "/terms/tos"));
	}

	/** 본문 주소는 없을 수 있다 — 빈 문자열이 아니라 {@code null} 이다. */
	@Test
	void R10_2_a_term_without_a_document_url_is_null_not_blank() {
		givenConfig(TERMS);

		assertThat(this.query.find("privacy")).contains(new ConsentTerm("1.0", null));
	}

	/** 설정이 없으면 비어 있다 — <b>기본 판본을 만들지 않는다</b> (#261). */
	@Test
	void R10_2_an_unset_term_is_empty() {
		given(this.configs.find(CatalogConsentTermsQuery.TERMS_KEY)).willReturn(Optional.empty());

		assertThat(this.query.find("tos")).isEmpty();
	}

	/** 설정은 있는데 그 종류가 없어도 같다. */
	@Test
	void R10_2_a_type_missing_from_the_configuration_is_empty() {
		givenConfig("{\"tos\": {\"version\":\"1.2\"}}");

		assertThat(this.query.find("privacy")).isEmpty();
	}

	/** 모양이 어긋나도 예외를 올리지 않는다 — 설정 하나 때문에 화면이 죽지 않는다. */
	@Test
	void R10_2_a_non_json_value_is_empty_not_an_exception() {
		givenConfig("이건 JSON 이 아니다");

		assertThat(this.query.find("tos")).isEmpty();
	}

	/**
	 * <b>{@code ai_notice} 의 판본은 고지 자체에서 온다</b> (R11.3, §13-8).
	 *
	 * <p>두 곳에 따로 적으면 고지를 갱신한 날 둘이 어긋나고, 어긋난 쪽이 동의 이력에 남는다.
	 */
	@Test
	void R11_3_the_ai_notice_version_comes_from_the_notice_itself() {
		givenConfig("{\"ai_notice\": {\"version\":\"엉뚱한 판본\"}}");
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-07-21", "고지 문구")));

		assertThat(this.query.find("ai_notice")).contains(new ConsentTerm("2026-07-21", null));
	}

	/** 고지가 설정되지 않았으면 그 동의도 비어 있다 (R11.1). */
	@Test
	void R11_1_an_unset_notice_leaves_the_ai_notice_term_empty() {
		given(this.notices.current()).willReturn(Optional.empty());

		assertThat(this.query.find("ai_notice")).isEmpty();
	}

	private void givenConfig(String raw) {
		given(this.configs.find(CatalogConsentTermsQuery.TERMS_KEY)).willReturn(Optional.of(raw));
	}
}
