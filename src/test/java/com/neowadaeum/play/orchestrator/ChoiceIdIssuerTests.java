package com.neowadaeum.play.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * B-21 DoD — <b>{@code choiceId} 가 {@code {sessionId, turnNo, order}} 기반이며 세션 안에서
 * 유일하고 재사용되지 않는다</b> (I-1, §13-9).
 *
 * <p>발급기 자체는 S-9 에서 만들어졌고 지금까지 <b>컨테이너가 필요한 파이프라인 테스트로만</b>
 * 확인됐다. 이 성질은 Docker 없이 확인할 수 있는 순수 계산이므로 빠른 루프에서 지킨다 (ADR-0001).
 */
class ChoiceIdIssuerTests {

	private static final UUID SESSION = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final UUID OTHER_SESSION = UUID.fromString("00000000-0000-4000-8000-000000000002");

	/** §13-9 — {@code {turnNo}-{order}-{shortHash}}. 앞의 두 조각이 좌표다. */
	@Test
	void S13_9_the_identifier_starts_with_the_turn_and_order_coordinate() {
		assertThat(ChoiceIdIssuer.issue(SESSION, 7, 2, "받는다")).matches("^7-2-[0-9a-f]{8}$");
	}

	/** 한 턴 안에서 유일하다 — 같은 화면의 선택지가 겹치면 어느 것을 고른 것인지 알 수 없다. */
	@Test
	void I1_identifiers_within_one_turn_are_unique() {
		List<String> issued = IntStream.rangeClosed(1, 4)
				.mapToObj(order -> ChoiceIdIssuer.issue(SESSION, 7, order, "선택 " + order))
				.toList();

		assertThat(issued).doesNotHaveDuplicates();
	}

	/**
	 * <b>이전 턴의 식별자는 재사용될 수 없다.</b>
	 *
	 * <p>턴 번호가 값에 들어 있으므로 같은 순서라도 턴이 다르면 값이 다르다 — 대조 단계에서 걸린다.
	 */
	@Test
	void I1_an_identifier_from_an_earlier_turn_cannot_be_replayed() {
		assertThat(ChoiceIdIssuer.issue(SESSION, 7, 1, "받는다"))
				.isNotEqualTo(ChoiceIdIssuer.issue(SESSION, 8, 1, "받는다"));
	}

	/** 세션이 다르면 값이 다르다 — 남의 것을 알아도 자기 세션에서는 쓸 수 없다. */
	@Test
	void I1_identifiers_do_not_collide_across_sessions() {
		assertThat(ChoiceIdIssuer.issue(SESSION, 7, 1, "받는다"))
				.isNotEqualTo(ChoiceIdIssuer.issue(OTHER_SESSION, 7, 1, "받는다"));
	}

	/**
	 * 같은 좌표라도 텍스트가 바뀌면 값이 바뀐다.
	 *
	 * <p>재생성된 턴(B-42)의 선택지가 <b>이전 턴의 식별자와 우연히 겹치는 것</b>을 막는다.
	 */
	@Test
	void S13_9_a_different_choice_text_at_the_same_coordinate_yields_a_different_identifier() {
		assertThat(ChoiceIdIssuer.issue(SESSION, 7, 1, "받는다"))
				.isNotEqualTo(ChoiceIdIssuer.issue(SESSION, 7, 1, "받지 않는다"));
	}

	/**
	 * I-15 — <b>난수를 쓰지 않는다.</b> 같은 입력은 언제나 같은 값이다.
	 *
	 * <p>결정론 E2E(B-44)가 식별자까지 재현할 수 있어야 한다. 추측 불가능성은 여기서 요구되는
	 * 성질이 아니다 — 식별자는 직전 턴이 발급한 것과 대조되기 때문이다.
	 */
	@Test
	void I15_the_same_input_always_yields_the_same_identifier() {
		List<String> repeated = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			repeated.add(ChoiceIdIssuer.issue(SESSION, 7, 1, "받는다"));
		}

		assertThat(repeated).containsOnly(ChoiceIdIssuer.issue(SESSION, 7, 1, "받는다"));
	}

	/** 좌표가 성립하지 않는 호출은 거부한다. 1부터 세는 값이다. */
	@Test
	void S13_9_a_coordinate_below_one_is_rejected() {
		assertThatThrownBy(() -> ChoiceIdIssuer.issue(SESSION, 0, 1, "받는다"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ChoiceIdIssuer.issue(SESSION, 1, 0, "받는다"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ChoiceIdIssuer.issue(null, 1, 1, "받는다"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * S-3 · S-11 — 식별자에 선택지 텍스트가 그대로 실리지 않는다.
	 *
	 * <p>{@code choiceId} 는 로그와 클라이언트 양쪽에 흐른다. 해시가 아니라 텍스트를 붙이면
	 * <b>본문 일부가 그 두 곳으로 함께 나간다.</b>
	 */
	@Test
	void SEC3_the_identifier_does_not_carry_the_choice_text() {
		assertThat(ChoiceIdIssuer.issue(SESSION, 7, 1, "유나에게 연락처를 묻는다"))
				.doesNotContain("유나", "연락처");
	}
}
