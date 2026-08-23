package com.neowadaeum.play.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 챕터 전환을 판정한다 (B-28 / S-7, §4.5).
 *
 * <p><b>R7.1 — AI 응답에서 추정하지 않는다.</b> 이 클래스에는 {@code chapterAdvanceSuggested} 를
 * <b>받을 파라미터가 없다.</b> 무시하는 코드를 쓰는 대신 받을 자리를 만들지 않았다 — 추정하면
 * 인터스티셜이 무작위로 뜬다.
 *
 * <p><b>R7.2 평가 순서</b>
 *
 * <pre>
 * 현재 챕터 min_turns 충족? ── no  → 유지
 *         └ yes → 다음 챕터 entry_condition 만족? ── yes → 전환
 *                                                  └ no  → max_turns 도달? ── yes → 강제 전환
 *                                                                            └ no  → 유지
 * </pre>
 *
 * <p><b>I-15</b> — 난수가 없다. 판정은 {@link ConditionEvaluator} 를 통한 GameState 평가뿐이다.
 *
 * <p>상태를 바꾸지 않는다. 챕터 번호를 실제로 올리는 것은 {@link GameState#advanceTo(int, int)} 이며
 * 그 호출은 S-9 의 몫이다.
 */
public class ChapterEngine {

	private final ConditionEvaluator evaluator;

	public ChapterEngine(ConditionEvaluator evaluator) {
		this.evaluator = evaluator;
	}

	/**
	 * @param chapters               이 작품 버전의 챕터 전부. 순서는 신경 쓰지 않는다
	 * @param currentChapterNo       현재 챕터
	 * @param turnsInCurrentChapter  현재 챕터에서 지난 턴 수. 근거는 {@code turn.chapter_no} 이며
	 *                               집계는 호출자(S-9)가 한다 — 파생 가능한 값을 컬럼으로 두지 않는다
	 * @param state                  판정 대상 상태
	 */
	public ChapterDecision decide(List<ChapterDefinition> chapters, int currentChapterNo,
			int turnsInCurrentChapter, GameState state) {
		if (chapters == null || chapters.isEmpty()) {
			throw new IllegalArgumentException("chapter definitions are required");
		}
		if (state == null) {
			throw new IllegalArgumentException("state is required");
		}

		ChapterDefinition current = find(chapters, currentChapterNo)
				.orElseThrow(() -> new IllegalArgumentException("unknown chapter: " + currentChapterNo));

		// R7.2 — min_turns 를 채우기 전에는 조건을 보지도 않는다.
		if (turnsInCurrentChapter < current.minTurns()) {
			return ChapterDecision.stay(currentChapterNo);
		}

		Optional<ChapterDefinition> next = find(chapters, currentChapterNo + 1);
		if (next.isEmpty()) {
			// 다음 번호가 없다고 곧바로 "마지막 챕터"로 보지 않는다.
			//
			// 챕터 번호는 UNIQUE(story_version_id, chapter_no) 로 유일하지만 **연속성은 강제되지
			// 않는다** — 작성 도중 삭제나 잘못된 시드로 [1, 3] 같은 구멍이 생길 수 있다.
			// 그때 1장을 마지막으로 오판하면 세션은 3장에 영영 닿지 못하고, 증상은 "엔딩이
			// 하나밖에 안 나온다"로 나타난다. 조용히 틀리는 쪽이라 여기서 끊는다.
			if (hasChapterBeyond(chapters, currentChapterNo)) {
				throw new IllegalStateException(
						"chapter numbering has a gap after " + currentChapterNo + " — the story is not playable");
			}

			// 진짜 마지막 챕터다. 갈 곳이 없으므로 강제 전환도 일어나지 않는다 — 여기서 끝나는 것은
			// 챕터가 아니라 세션이고, 그 판정은 EndingEngine 이 한다 (R7.7).
			return ChapterDecision.stay(currentChapterNo);
		}

		if (satisfiesEntryCondition(next.get(), state)) {
			return ChapterDecision.advance(next.get().chapterNo(), false);
		}

		// R7.2 — 조건이 끝내 만족되지 않아도 max_turns 에서는 넘긴다. 이것이 없으면 한 챕터에
		// 갇혀 무한히 진행된다.
		if (turnsInCurrentChapter >= current.maxTurns()) {
			return ChapterDecision.advance(next.get().chapterNo(), true);
		}

		return ChapterDecision.stay(currentChapterNo);
	}

	/**
	 * {@code entry_condition} 이 {@code null} 이면 "진입 조건 없음"이며 통과한다.
	 *
	 * <p>{@link ConditionEvaluator} 가 {@code null} 을 거부하는 이유가 이것이다 — {@code ending_def} 의
	 * {@code null} 은 정반대로 "조건 판정에 참여하지 않음"을 뜻한다 (§13-16). 해석은 호출자가 한다.
	 */
	private boolean satisfiesEntryCondition(ChapterDefinition chapter, GameState state) {
		var condition = chapter.entryCondition();
		if (condition == null || condition.isNull() || condition.isMissingNode()) {
			return true;
		}
		return this.evaluator.evaluate(condition, state);
	}

	private static boolean hasChapterBeyond(List<ChapterDefinition> chapters, int chapterNo) {
		return chapters.stream().anyMatch(chapter -> chapter.chapterNo() > chapterNo);
	}

	private static Optional<ChapterDefinition> find(List<ChapterDefinition> chapters, int chapterNo) {
		return chapters.stream()
				.filter(chapter -> chapter.chapterNo() == chapterNo)
				.min(Comparator.comparingInt(ChapterDefinition::chapterNo));
	}

	/**
	 * 판정 결과.
	 *
	 * @param chapterNo 판정 후의 챕터 번호
	 * @param changed   전환이 일어났는가. 응답의 {@code chapterChanged} 근거다 (R7.3)
	 * @param forced    조건 만족이 아니라 {@code max_turns} 도달로 넘어갔는가. 로그·관측용이다
	 */
	public record ChapterDecision(int chapterNo, boolean changed, boolean forced) {

		static ChapterDecision stay(int chapterNo) {
			return new ChapterDecision(chapterNo, false, false);
		}

		static ChapterDecision advance(int chapterNo, boolean forced) {
			return new ChapterDecision(chapterNo, true, forced);
		}
	}
}
