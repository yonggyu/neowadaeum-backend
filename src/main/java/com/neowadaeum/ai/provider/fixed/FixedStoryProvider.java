package com.neowadaeum.ai.provider.fixed;

import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.TurnRequest;
import com.neowadaeum.ai.provider.TurnResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 시나리오 파일이 정한 응답을 그대로 돌려주는 결정론 Provider (S-3, B-44 선행).
 *
 * <p>실 AI 없이 턴 파이프라인을 무한 반복하기 위한 <b>개발 도구</b>다 (ADR-0004). 비용·지연·비결정성이
 * 없으므로 S-8(규칙 기반 L2)과 S-9(턴 오케스트레이터)를 이것 위에서 검증한다.
 *
 * <p><b>I-15 — 난수가 없다.</b> 같은 {@code (storyVersionRef, turnNo, chosenChoiceOrder)} 에는 언제나
 * 같은 응답이 나온다. 조회는 불변 맵 하나이며 그 외의 판단 경로가 없다.
 *
 * <p><b>I-13 — 이 Provider 의 응답도 Safety L2 를 거친다.</b> 검수는 provider 와 무관하게 항상 서버에서
 * 수행되며 (S-8), 여기서 우회되지 않는다.
 *
 * <p><b>§0.2 — 스텁이 아니라 축소된 실물이다.</b> 시나리오에 없는 요청은 그럴듯한 값을 지어내지 않고
 * {@link UnsupportedOperationException} 을 던진다. "일단 통과"시키면 파이프라인이 검증되지 않은 채
 * 초록으로 보인다.
 *
 * <p><b>{@code prod} 에는 등록되지 않는다</b> (R3.1, I-14). {@link FixedStoryProviderConfiguration} 참조.
 */
public class FixedStoryProvider implements StoryProvider {

	public static final String PROVIDER_ID = "fixed";

	private final Map<ScenarioKey, TurnResult> responses;

	public FixedStoryProvider(List<FixedStoryScenario> scenarios) {
		this.responses = index(scenarios);
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	public TurnResult generateTurn(TurnRequest request) {
		ScenarioKey key = ScenarioKey.of(request);
		TurnResult result = responses.get(key);

		if (result == null) {
			// 요청의 좌표만 남긴다. 본문·선택지 텍스트는 애플리케이션 로그로 흘려보내지 않는다 (S-3).
			throw new UnsupportedOperationException("no fixed-story entry for " + key);
		}
		return result;
	}

	private static Map<ScenarioKey, TurnResult> index(List<FixedStoryScenario> scenarios) {
		if (scenarios == null || scenarios.isEmpty()) {
			throw new IllegalArgumentException("FixedStoryProvider needs at least one scenario");
		}

		Map<ScenarioKey, TurnResult> indexed = new HashMap<>();
		for (FixedStoryScenario scenario : scenarios) {
			for (FixedStoryScenario.Entry entry : scenario.entries()) {
				ScenarioKey key = new ScenarioKey(scenario.storyVersionRef(), entry.turnNo(),
						entry.chosenChoiceOrder());

				TurnResult previous = indexed.put(key, entry.toResult());
				if (previous != null) {
					// 중복 키는 "어느 쪽이 이기는가"를 파일 순서에 맡기게 된다. 결정론이 무너지는 지점이다.
					throw new IllegalArgumentException("duplicate fixed-story entry for " + key);
				}
			}
		}
		return Map.copyOf(indexed);
	}

	/** 결정론 조회 키. {@code (작품 버전, 요청 턴, 고른 선택지)} 하나가 응답 하나에 대응한다. */
	private record ScenarioKey(UUID storyVersionRef, int turnNo, Integer chosenChoiceOrder) {

		static ScenarioKey of(TurnRequest request) {
			return new ScenarioKey(request.storyVersionRef(), request.turnNo(), request.chosenChoiceOrder());
		}

		@Override
		public String toString() {
			return "storyVersion=%s turnNo=%d chosenChoiceOrder=%s".formatted(storyVersionRef, turnNo,
					chosenChoiceOrder);
		}
	}
}
