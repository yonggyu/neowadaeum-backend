package com.neowadaeum.authoring.precheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.authoring.blocklist.InMemoryBlocklistQuery;
import com.neowadaeum.authoring.draft.DraftSafetyState;
import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.BlocklistQuery;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.support.TextNormalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B-50 — <b>어디를 고쳐야 하는지 알려 준다</b> (R8.2).
 *
 * <p>{@code "부적절한 내용입니다"} 는 고칠 수 없는 안내다. 위치와 사유가 함께 와야 한다.
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b>
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class PrecheckScreenTests {

	/** 가상의 이름. */
	private static final String FICTIONAL = "이나린";

	/** 깨끗한 입력은 통과한다 — 문이 닫혀만 있으면 문이 아니다. */
	@Test
	void R8_2_a_clean_field_produces_no_finding() {
		PrecheckScreen screen = screenWith(entry(FICTIONAL, SafetyCategory.REAL_PERSON_HARM));

		PrecheckScreen.Result result = screen.screen(Map.of("worldIntro", "봄의 학교에서 시작한다."));

		assertThat(result.state()).isEqualTo(DraftSafetyState.CLEAN);
		assertThat(result.findings()).isEmpty();
	}

	/**
	 * <b>span 이 원문의 그 자리를 가리킨다</b> (R8.2).
	 *
	 * <p>클라이언트가 그 자리에 밑줄을 긋는다 — 정규화 뒤의 인덱스를 그대로 주면 엉뚱한 곳에
	 * 그어진다.
	 */
	@Test
	void R8_2_the_span_points_at_the_original_text() {
		PrecheckScreen screen = screenWith(entry(FICTIONAL, SafetyCategory.REAL_PERSON_HARM));
		String value = "어제 " + FICTIONAL + " 을 만났다.";

		PrecheckScreen.Result result = screen.screen(Map.of("worldIntro", value));

		assertThat(result.findings()).singleElement().satisfies(finding -> {
			assertThat(value.substring(finding.span()[0], finding.span()[1])).isEqualTo(FICTIONAL);
			assertThat(finding.field()).isEqualTo("worldIntro");
			assertThat(finding.kind()).isEqualTo("real_person_harm");
		});
	}

	/** <b>우회 표기도 걸리고, 그 자리도 가리킨다</b> (R9.2). */
	@Test
	void R9_2_an_evasive_spelling_is_found_with_its_span() {
		PrecheckScreen screen = screenWith(entry(FICTIONAL, SafetyCategory.REAL_PERSON_HARM));
		String value = "어제 이 나 린 을 만났다.";

		PrecheckScreen.Result result = screen.screen(Map.of("worldIntro", value));

		assertThat(result.findings()).singleElement().satisfies(finding -> {
			assertThat(value.substring(finding.span()[0], finding.span()[1])).isEqualTo("이 나 린");
		});
	}

	/** <b>같은 항목이 여러 번 나오면 자리마다 남는다.</b> 두 번째를 빼면 그 자리는 고쳐지지 않는다. */
	@Test
	void R8_2_every_occurrence_gets_its_own_finding() {
		PrecheckScreen screen = screenWith(entry(FICTIONAL, SafetyCategory.REAL_PERSON_HARM));

		PrecheckScreen.Result result = screen
				.screen(Map.of("worldIntro", FICTIONAL + " 과 " + FICTIONAL));

		assertThat(result.findings()).hasSize(2);
		assertThat(result.findings().get(0).span()).isNotEqualTo(result.findings().get(1).span());
	}

	/** 필드마다 따로 센다 — 어느 칸을 고쳐야 하는지가 그 정보다. */
	@Test
	void R8_2_findings_carry_the_field_they_came_from() {
		PrecheckScreen screen = screenWith(entry(FICTIONAL, SafetyCategory.REAL_PERSON_HARM));

		PrecheckScreen.Result result = screen.screen(new java.util.LinkedHashMap<>(
				Map.of("characters[0].name", FICTIONAL, "worldIntro", "봄의 학교")));

		assertThat(result.findings()).singleElement()
				.extracting(PrecheckFinding::field).isEqualTo("characters[0].name");
	}

	/**
	 * <b>안내가 무엇이 문제인지 말한다</b> (R8.2).
	 *
	 * <p>그리고 <b>걸린 항목은 말하지 않는다</b> (R8.7, S-11) — 응답이 우회 사전이 되면 안 된다.
	 */
	@Test
	void R8_7_the_message_explains_without_naming_the_entry() {
		PrecheckScreen screen = screenWith(entry(FICTIONAL, SafetyCategory.REAL_PERSON_HARM));

		PrecheckScreen.Result result = screen.screen(Map.of("worldIntro", FICTIONAL));

		assertThat(result.findings()).singleElement().satisfies(finding -> {
			assertThat(finding.message()).contains("실존 인물").doesNotContain(FICTIONAL);
		});
	}

	/** 빈 필드는 검사 대상이 아니다 — 아직 쓰지 않은 칸에 밑줄을 긋지 않는다. */
	@Test
	void R8_1_a_blank_field_is_skipped() {
		PrecheckScreen screen = screenWith(entry(FICTIONAL, SafetyCategory.REAL_PERSON_HARM));

		assertThat(screen.screen(Map.of("worldIntro", "   ")).findings()).isEmpty();
	}

	/**
	 * <b>fail-closed 를 뒤집지 않는다</b> (ADR-0002).
	 *
	 * <p>못 읽는 상태에서 <b>깨끗하다</b>고 답하면 작성자는 그 말을 믿고 제출한다.
	 */
	@Test
	void ADR0002_a_broken_blocklist_is_not_reported_as_clean() {
		BlocklistQuery broken = () -> {
			throw new IllegalStateException("조회 실패");
		};

		assertThatThrownBy(() -> new PrecheckScreen(broken).screen(Map.of("worldIntro", "봄")))
				.isInstanceOf(IllegalStateException.class);
	}

	private static PrecheckScreen screenWith(BlocklistEntry... entries) {
		return new PrecheckScreen(new InMemoryBlocklistQuery(List.of(entries)));
	}

	private static BlocklistEntry entry(String raw, SafetyCategory category) {
		return new BlocklistEntry(TextNormalizer.normalize(raw), category);
	}
}
