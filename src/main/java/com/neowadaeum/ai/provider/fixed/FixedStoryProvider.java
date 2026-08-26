package com.neowadaeum.ai.provider.fixed;

import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.play.port.SummaryRequest;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.common.support.TokenCounter;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.TurnRequest;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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

	private final Map<ScenarioKey, GeneratedTurn> responses;

	/**
	 * 판정 대상 텍스트 → 시나리오가 선언한 카테고리 (B-30).
	 *
	 * <p><b>키가 텍스트인 이유.</b> 판정기가 받는 것은 요청 좌표가 아니라 <b>이미 생성된 문자열</b>
	 * 이다 (I-12 — 판정기는 무엇이 그것을 만들었는지 모른다). 그래서 같은 좌표로 되짚지 못하고,
	 * 시나리오가 만든 텍스트 자체를 키로 삼는다.
	 */
	private final Map<String, List<SafetyCategory>> verdicts;

	/**
	 * 요약 예산을 지키려면 세는 수단이 필요하다 (B-34).
	 *
	 * <p><b>{@code common} 의 계산기를 그대로 쓴다</b> (#82). 여기서 따로 세면 <b>조립기가 넘는다고
	 * 보는 요약을 이 Provider 는 안 넘는다고 보는</b> 상태가 생긴다.
	 */
	private final TokenCounter tokens;

	public FixedStoryProvider(List<FixedStoryScenario> scenarios, TokenCounter tokens) {
		this.responses = index(scenarios);
		this.verdicts = indexVerdicts(scenarios);
		this.tokens = tokens;
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	/**
	 * <b>모델을 부르지 않는다.</b> 응답은 시나리오 파일이 정하므로 출력은 이미 구조화되어 있고,
	 * 프롬프트를 소비하지 않아 컨텍스트 상한이 의미를 갖지 않는다 (B-20 이 이 값을 읽는다).
	 */
	@Override
	public ProviderCapabilities capabilities() {
		return ProviderCapabilities.withoutModel();
	}

	@Override
	public GeneratedTurn generateTurn(TurnRequest request) {
		ScenarioKey key = ScenarioKey.of(request);
		GeneratedTurn result = responses.get(key);

		if (result == null) {
			// 요청의 좌표만 남긴다. 본문·선택지 텍스트는 애플리케이션 로그로 흘려보내지 않는다 (S-3).
			throw new UnsupportedOperationException("no scenario entry for " + key);
		}
		return result;
	}

	/**
	 * 시나리오가 선언한 판정 결과를 그대로 돌려준다 (B-30).
	 *
	 * <p><b>모델을 부르지 않는다는 성질은 여기서도 같다</b> — 판정 결과 역시 파일이 정한다. 그래서
	 * 세이프티 경로가 E2E 에서 <b>재현 가능</b>하다: 차단되는 턴을 선언해 두면 언제나 차단된다
	 * (I-15).
	 *
	 * <p><b>선언이 없으면 빈 집합이다.</b> 이것은 스텁이 아니라 <b>"이 텍스트에는 아무것도 걸리지
	 * 않는다"는 고정 응답</b>이다 (§0.2) — 1단(정규화 + 블록리스트)은 그와 무관하게 그대로 돈다.
	 */
	@Override
	public Set<SafetyCategory> classifySafety(SafetyClassificationRequest request) {
		Set<SafetyCategory> hits = new LinkedHashSet<>();
		request.texts().forEach(text -> hits.addAll(this.verdicts.getOrDefault(text, List.of())));
		return hits;
	}

	/**
	 * 발췌 요약 — <b>모델이 아니라 규칙이 만든다</b> (B-34).
	 *
	 * <p><b>지어내지 않는다.</b> 넘겨받은 직전 요약과 턴 요지를 순서대로 잇고, 예산을 넘으면
	 * <b>오래된 쪽부터 버린다.</b> 그래서 같은 입력에 언제나 같은 결과가 나온다 (I-15) — E2E 가
	 * 40턴을 돌려도 프롬프트에 실리는 과거가 흔들리지 않는다.
	 *
	 * <p><b>스텁이 아닌 이유가 여기 있다</b> (§0.2). 빈 문자열을 돌려주는 구현은 "요약이 붙었다"고
	 * 착각하게 만들지만, 이것은 <b>실제로 압축한다</b> — 재압축(R4.5)을 걸면 실제로 짧아진다.
	 * 모델이 하는 <b>의미 기반</b> 압축이 아니라는 것만 다르다.
	 */
	@Override
	public String summarize(SummaryRequest request) {
		List<String> lines = new java.util.ArrayList<>();
		if (request.previousSummary() != null && !request.previousSummary().isBlank()) {
			lines.add(request.previousSummary().strip());
		}
		request.turns().forEach(turn -> lines.add(line(turn)));

		// 예산을 넘으면 앞(오래된 쪽)부터 버린다. R4.5 의 재압축이 실제로 짧아지게 만드는 규칙이다.
		while (lines.size() > 1 && this.tokens.count(String.join("\n", lines)) > request.maxTokens()) {
			lines.removeFirst();
		}
		return String.join("\n", lines);
	}

	private static String line(SummaryRequest.TurnDigest turn) {
		String chosen = (turn.chosenChoiceText() != null && !turn.chosenChoiceText().isBlank())
				? " (선택: " + turn.chosenChoiceText().strip() + ")"
				: "";
		return turn.turnNo() + "턴" + chosen + ": " + turn.paragraphsDigest().strip();
	}

	/** <b>아웃라인 초안도 이 Provider 의 일이 아니다</b> (B-52). 위와 같은 이유다. */
	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		throw new UnsupportedOperationException("draftOutline is B-52; the fixed provider does not draft outlines");
	}

	/**
	 * 항목이 만든 문단·선택지 텍스트에 그 항목의 선언을 건다.
	 *
	 * <p>판정기는 문단과 선택지를 함께 받으므로(§9.1 의 L2 행) 양쪽 텍스트를 모두 키로 넣는다.
	 */
	private static Map<String, List<SafetyCategory>> indexVerdicts(List<FixedStoryScenario> scenarios) {
		Map<String, List<SafetyCategory>> verdicts = new HashMap<>();
		for (FixedStoryScenario scenario : scenarios) {
			for (FixedStoryScenario.Entry entry : scenario.entries()) {
				if (entry.safetyCategories().isEmpty()) {
					continue;
				}
				entry.paragraphs().forEach(paragraph -> verdicts.put(paragraph.text(), entry.safetyCategories()));
				entry.choices().forEach(choice -> verdicts.put(choice.text(), entry.safetyCategories()));
			}
		}
		return Map.copyOf(verdicts);
	}

	private static Map<ScenarioKey, GeneratedTurn> index(List<FixedStoryScenario> scenarios) {
		if (scenarios == null || scenarios.isEmpty()) {
			throw new IllegalArgumentException("FixedStoryProvider needs at least one scenario");
		}

		Map<ScenarioKey, GeneratedTurn> indexed = new HashMap<>();
		for (FixedStoryScenario scenario : scenarios) {
			for (FixedStoryScenario.Entry entry : scenario.entries()) {
				ScenarioKey key = new ScenarioKey(scenario.storyVersionRef(), entry.turnNo(),
						entry.chosenChoiceOrder());

				GeneratedTurn previous = indexed.put(key, entry.toGeneratedTurn());
				if (previous != null) {
					// 중복 키는 "어느 쪽이 이기는가"를 파일 순서에 맡기게 된다. 결정론이 무너지는 지점이다.
					throw new IllegalArgumentException("duplicate scenario entry for " + key);
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
