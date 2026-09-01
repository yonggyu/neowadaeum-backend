package com.neowadaeum.safety.l1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.authoring.blocklist.InMemoryBlocklistQuery;
import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.BlocklistQuery;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.support.TextNormalizer;
import com.neowadaeum.safety.l2.RuleBasedSafetyJudge;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * B-43 — <b>사람이 넣은 텍스트도 검수를 지난다</b> (I-17, R14.1).
 *
 * <p><b>S-11 — 픽스처는 전부 가상의 문자열이다.</b> 판정은 정규화 값끼리의 대조이므로 지어낸
 * 문자열로도 똑같이 검증된다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class InputSafetyScreenTests {

	/** 가상의 이름. */
	private static final String FICTIONAL_NAME = "이나린";

	/** 깨끗한 입력이 통과한다 — 문이 닫혀만 있으면 문이 아니다. */
	@Test
	void R14_1_a_clean_input_passes() {
		InputSafetyScreen screen = screenWith(entry(FICTIONAL_NAME, SafetyCategory.REAL_PERSON_HARM));

		InputVerdict verdict = screen.screen("창밖을 본다");

		assertThat(verdict.blocked()).isFalse();
		assertThat(verdict.categories()).isEmpty();
	}

	/** <b>관리자라는 사실이 검수를 면제하지 않는다</b> (I-17). */
	@Test
	void I17_a_blocked_term_does_not_get_in() {
		InputSafetyScreen screen = screenWith(entry(FICTIONAL_NAME, SafetyCategory.REAL_PERSON_HARM));

		InputVerdict verdict = screen.screen(FICTIONAL_NAME + " 에게 말을 건다");

		assertThat(verdict.blocked()).isTrue();
		assertThat(verdict.categories()).containsExactly(SafetyCategory.REAL_PERSON_HARM);
	}

	/**
	 * <b>우회 표기도 걸린다</b> (R9.2).
	 *
	 * <p>같은 대조기를 쓰므로 성립한다 — 여기에 매칭을 따로 구현했다면 <b>한쪽으로 들어온
	 * 문자열이 다른 쪽에서는 걸리는</b> 상태가 됐을 것이다.
	 */
	@Test
	void R9_2_evasive_spellings_are_caught_here_too() {
		InputSafetyScreen screen = screenWith(entry(FICTIONAL_NAME, SafetyCategory.REAL_PERSON_HARM));

		assertThat(screen.screen("이 나 린 을 본다").blocked()).isTrue();
		assertThat(screen.screen("1나린 을 본다").blocked()).isTrue();
	}

	/**
	 * §9.2 — <b>입력에서는 가리지 않는다. 거부한다.</b>
	 *
	 * <p>같은 카테고리라도 처리가 갈린다: 생성물은 가린 뒤 통과하지만 <b>UGC 입력은 차단</b>이다.
	 * 넣은 사람의 문장을 서버가 고쳐서 들이는 경로를 만들지 않는다.
	 */
	@Test
	void R9_2_third_party_personal_data_in_input_is_refused_not_masked() {
		InputSafetyScreen screen = screenWith(entry(FICTIONAL_NAME, SafetyCategory.THIRD_PARTY_PERSONAL_DATA));

		InputVerdict verdict = screen.screen(FICTIONAL_NAME + " 에게 전화한다");

		assertThat(verdict.blocked()).isTrue();
		assertThat(verdict.categories()).containsExactly(SafetyCategory.THIRD_PARTY_PERSONAL_DATA);
	}

	/**
	 * §9.2 — <b>재생성 대상 카테고리도 입력에서는 거부다.</b>
	 *
	 * <p>출력에는 "다시 만든다"는 선택지가 있지만 입력에는 없다 — 다시 만들 것이 없다.
	 */
	@Test
	void R9_2_a_regenerate_category_is_refused_at_the_input_gate() {
		InputSafetyScreen screen = screenWith(entry(FICTIONAL_NAME, SafetyCategory.IP_REPLICATION));

		assertThat(screen.screen(FICTIONAL_NAME + " 처럼 말한다").blocked()).isTrue();
	}

	/** 빈 입력은 검수 대상이 아니다 — 무엇이 유효한 입력인가는 부르는 쪽의 판단이다. */
	@Test
	void R14_1_blank_input_is_not_screened() {
		InputSafetyScreen screen = screenWith(entry(FICTIONAL_NAME, SafetyCategory.REAL_PERSON_HARM));

		assertThat(screen.screen(null).blocked()).isFalse();
		assertThat(screen.screen("   ").blocked()).isFalse();
	}

	/**
	 * <b>fail-closed</b> (ADR-0002) — 블록리스트를 읽지 못하면 들이지 않는다.
	 *
	 * <p>못 읽는 상태에서 통과시키면 블록리스트가 존재하지 않는 것과 같다.
	 */
	@Test
	void I17_a_broken_blocklist_blocks_rather_than_opens() {
		BlocklistQuery broken = () -> {
			throw new IllegalStateException("조회 실패");
		};
		InputSafetyScreen screen = new InputSafetyScreen(new RuleBasedSafetyJudge(broken));

		assertThat(screen.screen("창밖을 본다").blocked()).isTrue();
	}

	/** 대조기 없이 세워지지 않는다 — 검수 없는 검수기를 만들 수 없다. */
	@Test
	void I17_the_screen_cannot_exist_without_a_judge() {
		assertThatThrownBy(() -> new RuleBasedSafetyJudge(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static InputSafetyScreen screenWith(BlocklistEntry... entries) {
		return new InputSafetyScreen(
				new RuleBasedSafetyJudge(new InMemoryBlocklistQuery(List.of(entries))));
	}

	private static BlocklistEntry entry(String raw, SafetyCategory category) {
		return new BlocklistEntry(TextNormalizer.normalize(raw), category);
	}
}
