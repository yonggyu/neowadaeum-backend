package com.neowadaeum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.ConsentTerm;
import com.neowadaeum.common.spi.ConsentTermsQuery;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 필수 운영 데이터 목록의 성질 (§13-89, 이슈 #426).
 *
 * <p>여기서 고정하는 것은 <b>무엇을 세는가</b>다. 목록이 늘거나 줄면 이 테스트가 먼저 말한다 —
 * 그것이 목록의 정본을 코드에 둔 이유다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class RequiredOperationalDataTests {

	private static final AiNotice NOTICE = new AiNotice("2026-07-21", "이 이야기는 AI가 생성합니다.");

	/**
	 * <b>독립된 값은 셋이다</b> (§13-51).
	 *
	 * <p>동의 종류는 넷이지만 {@code age} 는 서버가 조립하고 {@code ai_notice} 판본은
	 * {@code ai.notice} 에서 파생된다 — 파생값을 세면 <b>같은 결함이 두 번 보고된다.</b>
	 */
	@Test
	void S13_89_the_required_data_are_the_three_independent_values() {
		assertThat(RequiredOperationalData.missing(() -> Optional.empty(), consentType -> Optional.empty()))
				.containsExactly("ai.notice", "consent.terms.tos", "consent.terms.privacy")
				.doesNotContain("consent.terms.ai_notice", "consent.terms.age");
	}

	/** 하나만 빠져도 그것만 이름이 오른다 — 넣은 것은 다시 요구하지 않는다. */
	@Test
	void S13_89_only_what_is_absent_is_named() {
		assertThat(RequiredOperationalData.missing(() -> Optional.of(NOTICE), termsWithout("privacy")))
				.containsExactly("consent.terms.privacy");
	}

	/** 전부 있으면 비어 있다. */
	@Test
	void S13_89_nothing_is_missing_when_everything_is_configured() {
		assertThat(RequiredOperationalData.missing(() -> Optional.of(NOTICE),
				consentType -> Optional.of(new ConsentTerm("v1.0", null))))
				.isEmpty();
	}

	private static ConsentTermsQuery termsWithout(String absent) {
		return consentType -> absent.equals(consentType) ? Optional.empty()
				: Optional.of(new ConsentTerm("v1.0", null));
	}

}
