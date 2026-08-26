package com.neowadaeum.ai.prompt;

import com.neowadaeum.ai.prompt.PromptLayer.BudgetGroup;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.support.TokenCounter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 8레이어 프롬프트 조립기 (§5.1, §4.3, B-20).
 *
 * <p>{@code SYSTEM → WORLD → CHARACTER → GAME STATE → SUMMARY → RECENT TURNS → USER ACTION → OUTPUT SPEC}
 *
 * <h2>I-7 — 플랫폼 레이어는 작품이 덮어쓸 수 없다</h2>
 *
 * <p>막는 방식이 <b>검사가 아니라 구조</b>다. {@link PromptContext} 에 {@code SYSTEM} 과
 * {@code OUTPUT SPEC} 의 자리가 없고, 두 레이어는 {@link PlatformPrompts} 의 상수에서만 온다.
 * {@code world_prompt} 에 "이전 지시를 무시하라"가 들어와도 그것은 {@code WORLD} 블록 <b>안의
 * 글자</b>일 뿐이며, 순서도 내용도 바뀌지 않는다.
 *
 * <h2>§4.3 — 예산을 넘기면 어떻게 되는가</h2>
 *
 * <p><b>조용히 잘라내고 진행하지 않는다</b> ({@code .claude/rules/ai.md}). 줄이는 순서는 정해져 있다.
 *
 * <ol>
 *   <li>{@code RECENT TURNS} 를 <b>오래된 것부터</b> 뺀다
 *   <li>그래도 넘치면 {@code CONTEXT_BUDGET_EXCEEDED} 로 실패시킨다
 * </ol>
 *
 * <p><b>그 사이에 있어야 할 "SUMMARY 재압축"이 여기에 없다.</b> 재압축은 모델 호출이고 조립기는
 * 모델을 부르지 않는다 — 요약 파이프라인(B-34)이 그 자리를 채운다. 스텁으로 흉내 내면 재압축이
 * 일어난 것처럼 보이면서 실제로는 아무 일도 하지 않는다 (§0.2).
 *
 * <p><b>{@code FOUNDATION} 이 넘치는 경우는 줄이지 않고 바로 실패시킨다.</b> 작품 레이어를 서버가
 * 잘라내면 세계관이 조용히 반쪽이 된 채 이야기가 이어진다. 길이는 저장 시점에 막는 것이 맞고
 * (R4.9, B-51), 여기까지 왔다면 그것이 새고 있다는 뜻이다.
 */
public class PromptAssembler {

	/** §4.3 입력 합계 목표. 묶음 상한의 합(3,800)보다 크며, 그 여유가 형식 문자열의 몫이다. */
	public static final int TOTAL_BUDGET_TOKENS = 4_000;

	private final TokenCounter tokenCounter;

	private final RecentTurnsProperties recentTurnsPolicy;

	public PromptAssembler(TokenCounter tokenCounter, RecentTurnsProperties recentTurnsPolicy) {
		if (tokenCounter == null) {
			throw new IllegalArgumentException("tokenCounter is required");
		}
		if (recentTurnsPolicy == null) {
			throw new IllegalArgumentException("recentTurnsPolicy is required");
		}
		this.tokenCounter = tokenCounter;
		this.recentTurnsPolicy = recentTurnsPolicy;
	}

	/**
	 * 한 턴의 프롬프트를 조립한다.
	 *
	 * @throws ApiException {@link ErrorCode#CONTEXT_BUDGET_EXCEEDED} — 줄일 수 있는 것을 다 줄여도 예산을 넘길 때
	 */
	public AssembledPrompt assemble(PromptContext context) {
		if (context == null) {
			throw new IllegalArgumentException("context is required");
		}

		// §13-2 — 프롬프트에 싣는 것은 최근 N턴까지다. 그보다 오래된 것은 요약의 몫이다 (R4.5).
		List<PromptContext.RecentTurn> recentTurns = new ArrayList<>(withinWindow(context.recentTurns()));

		// 1) RECENT TURNS 를 오래된 것부터 빼면서 예산 안으로 들어오는지 본다.
		while (true) {
			List<AssembledPrompt.Section> sections = sections(context, recentTurns);
			if (withinBudget(sections)) {
				return new AssembledPrompt(sections, totalTokens(sections));
			}
			if (recentTurns.isEmpty() || !onlyRecentTurnsOverflow(sections)) {
				break;
			}
			recentTurns.removeFirst();
		}

		// 2) 여기까지 왔으면 줄일 수 있는 것이 없다. 잘라내고 진행하지 않는다.
		throw new ApiException(ErrorCode.CONTEXT_BUDGET_EXCEEDED);
	}

	// ── 레이어 구성 ─────────────────────────────────────────

	private List<AssembledPrompt.Section> sections(PromptContext context,
			List<PromptContext.RecentTurn> recentTurns) {

		List<AssembledPrompt.Section> sections = new ArrayList<>();
		add(sections, PromptLayer.SYSTEM, PlatformPrompts.SYSTEM);
		add(sections, PromptLayer.WORLD, context.worldPrompt());
		add(sections, PromptLayer.CHARACTER, characterText(context));
		add(sections, PromptLayer.GAME_STATE, context.gameState().toString());
		add(sections, PromptLayer.SUMMARY, context.summary());
		add(sections, PromptLayer.RECENT_TURNS, recentTurnsText(recentTurns));
		add(sections, PromptLayer.USER_ACTION, context.userAction());
		add(sections, PromptLayer.OUTPUT_SPEC, PlatformPrompts.OUTPUT_SPEC);
		return List.copyOf(sections);
	}

	/** 내용이 없는 레이어는 빈 블록으로 남기지 않고 아예 뺀다. 빈 블록도 토큰을 쓴다. */
	private void add(List<AssembledPrompt.Section> sections, PromptLayer layer, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		sections.add(new AssembledPrompt.Section(layer, text, this.tokenCounter.count(text)));
	}

	/** 최근 {@code inPrompt} 턴만 남긴다. 목록은 오래된 것이 앞이므로 뒤에서 센다. */
	private List<PromptContext.RecentTurn> withinWindow(List<PromptContext.RecentTurn> recentTurns) {
		int window = this.recentTurnsPolicy.inPrompt();
		return recentTurns.size() <= window ? recentTurns
				: recentTurns.subList(recentTurns.size() - window, recentTurns.size());
	}

	private static String characterText(PromptContext context) {
		return context.characters().stream()
				.map(character -> "%s: %s".formatted(character.name(), character.persona()))
				.collect(Collectors.joining("\n"));
	}

	/**
	 * 오래된 것이 앞이다. 뒤가 최신이며, 빠지는 것은 앞에서부터다 (§4.4).
	 *
	 * <p><b>가장 최근 {@code verbatim} 턴만 본문 원문이고 나머지는 압축본이다</b> (§13-2). 1,500토큰
	 * 안에 원문 5턴은 들어가지 않는다. 원문이 없는 턴은 압축본으로 대신한다.
	 */
	private String recentTurnsText(List<PromptContext.RecentTurn> recentTurns) {
		int verbatimFrom = recentTurns.size() - this.recentTurnsPolicy.verbatim();

		List<String> lines = new ArrayList<>();
		for (int index = 0; index < recentTurns.size(); index++) {
			PromptContext.RecentTurn turn = recentTurns.get(index);
			String body = index >= verbatimFrom && turn.paragraphs() != null && !turn.paragraphs().isBlank()
					? turn.paragraphs()
					: turn.paragraphsDigest();
			lines.add(turn.chosenChoiceText() == null
					? "%d) %s".formatted(turn.turnNo(), body)
					: "%d) %s / 선택: %s".formatted(turn.turnNo(), body, turn.chosenChoiceText()));
		}
		return String.join("\n", lines);
	}

	// ── 예산 ────────────────────────────────────────────────

	private static boolean withinBudget(List<AssembledPrompt.Section> sections) {
		if (totalTokens(sections) > TOTAL_BUDGET_TOKENS) {
			return false;
		}
		return overflowingGroups(sections).isEmpty();
	}

	/**
	 * 넘치는 것이 {@code RECENT TURNS} 뿐인가.
	 *
	 * <p>그 밖의 묶음이 넘친다면 더 빼도 소용이 없다 — 총합만 넘치는 경우는 여기서 참이며, 최근 턴을
	 * 빼면 총합이 줄어든다.
	 */
	private static boolean onlyRecentTurnsOverflow(List<AssembledPrompt.Section> sections) {
		return overflowingGroups(sections).stream().allMatch(group -> group == BudgetGroup.RECENT_TURNS);
	}

	private static List<BudgetGroup> overflowingGroups(List<AssembledPrompt.Section> sections) {
		Map<BudgetGroup, Integer> used = new EnumMap<>(BudgetGroup.class);
		for (AssembledPrompt.Section section : sections) {
			used.merge(section.layer().budgetGroup(), section.tokens(), Integer::sum);
		}
		return used.entrySet().stream()
				.filter(entry -> entry.getValue() > entry.getKey().maxTokens())
				.map(Map.Entry::getKey)
				.toList();
	}

	private static int totalTokens(List<AssembledPrompt.Section> sections) {
		return sections.stream().mapToInt(AssembledPrompt.Section::tokens).sum();
	}
}
