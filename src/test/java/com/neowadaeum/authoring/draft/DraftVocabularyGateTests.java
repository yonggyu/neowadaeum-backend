package com.neowadaeum.authoring.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.prompt.PromptStateVocabularyBudget;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.StateVocabularyBudget;
import com.neowadaeum.common.support.ApproximateTokenCounter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * §13-76 — <b>게이트는 개수가 아니라 예산이다</b> (#367).
 *
 * <p>§13-73 은 플래그 32개 · 40자를 <b>프롬프트 때문에</b> 정했다고 적었지만 그 프롬프트의
 * 상한과는 대조되지 않았다. 그리고 {@code characters} 에는 상한이 아예 없다. #367 의 레이어가
 * 붙는 순간 그 어긋남은 <b>그 작품의 모든 턴이 실패하는 것</b>으로 나타난다 — 세션은 버전에
 * 고정되므로 (I-4) 되돌릴 방법도 없다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class DraftVocabularyGateTests {

	/** 운영에서 쓰는 것과 같은 판정기다. 저장에서 통과한 것이 턴에서 실패하면 안 된다. */
	private final DraftVocabularyGate gate =
			new DraftVocabularyGate(new PromptStateVocabularyBudget(new ApproximateTokenCounter()));

	private static DraftStateSchema declaring(int characters, int flags, int nameLength) {
		Set<String> characterNames = new LinkedHashSet<>();
		for (int index = 0; index < characters; index++) {
			characterNames.add(padded("c" + index, nameLength));
		}
		Set<String> flagNames = new LinkedHashSet<>();
		for (int index = 0; index < flags; index++) {
			flagNames.add(padded("f" + index, nameLength));
		}
		return new DraftStateSchema(characterNames, flagNames);
	}

	/** 이름을 상한 길이까지 채운다. 앞이 서로 달라야 중복으로 접히지 않는다. */
	private static String padded(String prefix, int length) {
		return (prefix + "_").concat("x".repeat(Math.max(0, length - prefix.length() - 1)));
	}

	/**
	 * <b>경계에서 갈린다</b> — 40자 플래그 13개는 지나가고 14개는 걸린다.
	 *
	 * <p>이 두 수는 계산한 것이 아니라 <b>운영 계산기가 낸 값</b>이다. 게이트가 상한을 스스로
	 * 정하지 않고 {@code ai} 에게 묻는다는 사실이 여기서 값으로 드러난다 — 레이어 문구가 바뀌면
	 * 이 경계도 함께 움직이고, 그때 이 테스트가 먼저 빨개진다.
	 */
	@Test
	void S13_76_the_gate_splits_at_the_measured_boundary() {
		assertThatCode(() -> this.gate.verify(declaring(0, 13, 40))).doesNotThrowAnyException();

		assertThatThrownBy(() -> this.gate.verify(declaring(0, 14, 40)))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	/**
	 * <b>인물만으로도 걸린다</b> (§13-76).
	 *
	 * <p>상한이 없는 쪽이 인물이다. 플래그만 재면 <b>플래그를 하나도 안 쓴 원고</b>가 그대로
	 * 예산을 넘길 수 있고, 그 작품도 모든 턴에서 실패한다.
	 */
	@Test
	void S13_76_the_affinity_half_alone_can_trip_the_gate() {
		assertThatThrownBy(() -> this.gate.verify(declaring(20, 0, 40)))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * <b>넘긴 만큼을 알려 준다</b> — 넘었다는 사실만으로는 고칠 수 없다.
	 *
	 * <p>담기는 것은 <b>비율</b>이다. 토큰 수도 상한값도 응답에 넣지 않는다 (S-6) — 작성자가
	 * 아는 단위가 아니고, 그 수를 내보내면 계산 방식이 함께 나간다.
	 */
	@Test
	void SEC6_the_rejection_says_how_far_over_without_naming_tokens() {
		ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
				() -> this.gate.verify(declaring(0, 32, 40)));

		assertThat(thrown.details()).containsOnlyKeys("vocabularyUsagePercent");
		assertThat((Integer) thrown.details().get("vocabularyUsagePercent")).isGreaterThan(100);
		assertThat(thrown.getMessage()).doesNotContain("200", "token", "토큰");
	}

	/** 공식 작품 시드 규모(인물 3 · 플래그 6)는 여유가 크다. 게이트가 정상 작품을 막지 않는다. */
	@Test
	void S13_76_the_seed_story_sits_well_inside_the_budget() {
		DraftStateSchema seed = new DraftStateSchema(Set.of("yuna", "dohyun", "seri"),
				new LinkedHashSet<>(List.of("met_yuna", "shared_lunch", "joined_club",
						"kept_promise", "rainy_walk", "festival_night")));

		assertThatCode(() -> this.gate.verify(seed)).doesNotThrowAnyException();
	}

	/**
	 * <b>{@code authoring} 은 상한을 알지 못한다</b> (§13-76, ADR-0002 의 SPI 패턴).
	 *
	 * <p>구성으로 증명한다 — 판정기가 "다 들어간다"고 답하면 §13-73 의 상한을 가득 채운 선언도
	 * 지나간다. {@code authoring} 이 숫자를 복제해 두었다면 이 선언은 판정기와 <b>무관하게</b>
	 * 걸릴 것이고, 그 복제는 {@code ai} 가 레이어 문구를 고치는 날 조용히 어긋난다.
	 */
	@Test
	void S13_76_authoring_asks_for_the_limit_instead_of_knowing_it() {
		StateVocabularyBudget alwaysFits = new StateVocabularyBudget() {
			@Override
			public Usage assess(Collection<String> numericPaths, Collection<String> flags,
					Collection<String> inventory) {
				return new Usage(1);
			}
		};

		assertThatCode(() -> new DraftVocabularyGate(alwaysFits).verify(declaring(50, 32, 40)))
				.doesNotThrowAnyException();
	}

	/** 반대 방향도 같다 — 판정기가 넘쳤다고 하면 이름 하나짜리 원고도 걸린다. */
	@Test
	void S13_76_the_gate_follows_the_budget_upward_too() {
		StateVocabularyBudget neverFits = new StateVocabularyBudget() {
			@Override
			public Usage assess(Collection<String> numericPaths, Collection<String> flags,
					Collection<String> inventory) {
				return new Usage(101);
			}
		};

		assertThatThrownBy(() -> new DraftVocabularyGate(neverFits).verify(declaring(0, 1, 4)))
				.isInstanceOf(ApiException.class);
	}
}
