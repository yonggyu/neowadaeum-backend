package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.ConsentTerm;
import com.neowadaeum.common.spi.ConsentTermsQuery;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #261 — 가입 화면이 판본을 상수로 들고 있지 않게 한다 (R10.2).
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class ConsentTermsServiceTests {

	private final ConsentTermsQuery terms = mock(ConsentTermsQuery.class);

	private final ConsentTermsService service = new ConsentTermsService(this.terms);

	@BeforeEach
	void configureTerms() {
		given(this.terms.find("tos"))
				.willReturn(Optional.of(new ConsentTerm("v1.2", "https://example.invalid/terms/tos")));
		given(this.terms.find("privacy"))
				.willReturn(Optional.of(new ConsentTerm("v1.0", "https://example.invalid/terms/privacy")));
		given(this.terms.find("ai_notice")).willReturn(Optional.of(new ConsentTerm("2026-07-21", null)));
	}

	/** R10.2 — 네 종류가 가입 화면의 순서대로 나온다. */
	@Test
	void R10_2_every_consent_type_is_listed() {
		assertThat(this.service.terms().terms())
				.extracting(ConsentTermsView.Term::consentType)
				.containsExactly("tos", "privacy", "ai_notice", "age");
	}

	/** R10.2 — 판본이 설정에서 온다. 코드에 없다. */
	@Test
	void R10_2_versions_come_from_configuration() {
		assertThat(versionOf("tos")).isEqualTo("v1.2");
		assertThat(versionOf("privacy")).isEqualTo("v1.0");
		assertThat(versionOf("ai_notice")).isEqualTo("2026-07-21");
	}

	/**
	 * <b>§13-24 — {@code age} 판본은 서버가 정한다.</b>
	 *
	 * <p>기록하는 쪽({@code SocialAccountRegistrar})과 같은 출처를 본다 — 두 곳이 갈라지면
	 * 알려 준 판본과 기록된 판본이 달라진다.
	 */
	@Test
	void R10_2_the_age_version_is_decided_by_the_server() {
		assertThat(versionOf("age")).isEqualTo(com.neowadaeum.identity.auth.AgeGate.consentVersion());
	}

	/** {@code age} 만 사용자의 동의가 아니다 (R10.2). */
	@Test
	void R10_2_only_age_is_not_a_user_checkbox() {
		assertThat(this.service.terms().terms())
				.filteredOn(term -> !term.required())
				.extracting(ConsentTermsView.Term::consentType)
				.containsExactly("age");
	}

	/** 본문 주소가 없는 종류는 키를 생략하지 않고 {@code null} 이다 (web-api 규칙). */
	@Test
	void R10_2_a_missing_document_url_stays_null() {
		assertThat(this.service.terms().terms())
				.filteredOn(term -> term.consentType().equals("ai_notice"))
				.singleElement()
				.extracting(ConsentTermsView.Term::documentUrl)
				.isNull();
	}

	/**
	 * <b>설정이 없으면 실패한다 — 기본 판본을 지어내지 않는다</b> (#261, R10.2).
	 *
	 * <p>{@code "v1"} 같은 값을 주면 이슈가 고치려던 상태가 서버로 옮겨올 뿐이고, 그때는
	 * 프론트가 상수를 들고 있을 때보다 찾기 더 어렵다. {@code LandingService} 가 고지 문구에
	 * 대해 하는 것과 같은 판단이다.
	 */
	@Test
	void R10_2_an_unconfigured_term_fails_instead_of_inventing_a_version() {
		given(this.terms.find("tos")).willReturn(Optional.empty());

		assertThatThrownBy(this.service::terms)
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ErrorCode.INTERNAL_ERROR);
	}

	private String versionOf(String consentType) {
		return this.service.terms().terms().stream()
				.filter(term -> term.consentType().equals(consentType))
				.findFirst().orElseThrow().version();
	}
}
