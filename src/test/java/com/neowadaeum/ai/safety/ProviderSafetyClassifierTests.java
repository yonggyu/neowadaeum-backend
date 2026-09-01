package com.neowadaeum.ai.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ai.provider.TurnOnlyStoryProvider;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationFailedException;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.GenerationTimedOutException;
import com.neowadaeum.play.port.TurnRequest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 판정 SPI 의 ai 쪽 구현 (B-30).
 *
 * <p><b>fail-closed 가 전부다.</b> 여기서 확인하는 것은 "실패가 통과로 바뀌지 않는가" 하나이며,
 * 세이프티에서 fail-open 은 장애가 곧 검수 우회다 (ADR-0002 와 같은 성질).
 */
class ProviderSafetyClassifierTests {

	private static final SafetyClassificationRequest REQUEST =
			new SafetyClassificationRequest(List.of("판정할 문장"));

	/** 판정만 보는 테스트용 어댑터. 턴 생성은 이 테스트의 관심사가 아니다 (§0.2). */
	private abstract static class ClassifyOnlyStoryProvider extends TurnOnlyStoryProvider {

		@Override
		public GeneratedTurn generateTurn(TurnRequest request) {
			throw new UnsupportedOperationException("this test provider only classifies");
		}
	}

	/** 판정이 되면 그 결과가 그대로 올라온다. */
	@Test
	void R9_2_a_verdict_passes_through() {
		Set<SafetyCategory> verdict = new ProviderSafetyClassifier(new ClassifyOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "classifying";
			}

			@Override
			public Set<SafetyCategory> classifySafety(SafetyClassificationRequest request) {
				return Set.of(SafetyCategory.HATE_SPEECH);
			}
		}).classify(REQUEST);

		assertThat(verdict).containsExactly(SafetyCategory.HATE_SPEECH);
	}

	/**
	 * <b>판정을 구현하지 않은 어댑터는 통과가 아니다.</b>
	 *
	 * <p>{@link UnsupportedOperationException} 을 그대로 올려 보내면 호출자가 그것을 "판정 안 함"
	 * 으로 읽을 여지가 생긴다. 여기서 <b>판정 실패</b>로 좁힌다.
	 */
	@Test
	void B30_an_unimplemented_adapter_is_a_closed_failure() {
		assertThatThrownBy(() -> new ProviderSafetyClassifier(new ClassifyOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "turn-only";
			}
		}).classify(REQUEST))
				.isInstanceOf(SafetyClassificationFailedException.class);
	}

	/** 시간 초과도 마찬가지다 — 판정하지 못한 응답은 통과하지 못한다 (I-2). */
	@Test
	void B30_a_timeout_is_a_closed_failure() {
		assertThatThrownBy(() -> new ProviderSafetyClassifier(new ClassifyOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "slow";
			}

			@Override
			public Set<SafetyCategory> classifySafety(SafetyClassificationRequest request) {
				throw new GenerationTimedOutException(Duration.ofSeconds(25));
			}
		}).classify(REQUEST))
				.isInstanceOf(SafetyClassificationFailedException.class);
	}

	/** S-3 — 실패가 판정 대상 원문을 메시지에 싣지 않는다. */
	@Test
	void S3_the_failure_message_does_not_carry_the_judged_text() {
		String judged = "유나의 연락처는 010-0000-0000 이다";

		assertThatThrownBy(() -> new ProviderSafetyClassifier(new ClassifyOnlyStoryProvider() {
			@Override
			public String providerId() {
				return "failing";
			}

			@Override
			public Set<SafetyCategory> classifySafety(SafetyClassificationRequest request) {
				throw new IllegalStateException("boom");
			}
		}).classify(new SafetyClassificationRequest(List.of(judged))))
				.hasMessageNotContaining(judged);
	}
}
