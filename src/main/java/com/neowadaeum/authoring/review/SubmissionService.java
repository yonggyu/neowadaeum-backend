package com.neowadaeum.authoring.review;

import com.neowadaeum.authoring.UgcLimitProperties;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.DraftStoryDefinition;
import com.neowadaeum.authoring.draft.StoryDraft;
import com.neowadaeum.authoring.precheck.PrecheckFinding;
import com.neowadaeum.authoring.precheck.PrecheckScreen;
import com.neowadaeum.catalog.publish.StoryDefinition;
import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.spi.StoryReviewTimes;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 제출과 자동 검수 (§8.3, R8.5~R8.8).
 *
 * <p><b>승인이 곧 게시다</b> (R8.8) — 통과하면 버전이 발행되고 현재가 된다. 나누면 <b>승인됐는데
 * 플레이할 수 없는</b> 작품이 남는다.
 *
 * <p><b>{@code public} 은 자동 검수만으로 승인되지 않는다</b> (R8.6). 자동이 통과시켜도
 * {@code in_review} 에 머물며, 사람이 본 뒤에 열린다 (B-55).
 *
 * <p><b>반려 사유는 카테고리만이다</b> (R8.7). 어떤 항목에 걸렸는지를 알려 주면 <b>우회 학습을
 * 돕는다</b>.
 *
 * <p><b>같은 L1 을 쓴다.</b> 작성 중 통과한 것이 제출에서 걸리면 작성 중 피드백은 거짓 안심이
 * 되고, 반대면 제출 검수가 무의미하다.
 */
@Service
public class SubmissionService {

	private static final tools.jackson.databind.json.JsonMapper JSON =
			tools.jackson.databind.json.JsonMapper.builder().build();

	private final DraftService drafts;

	private final PrecheckScreen screen;

	private final StoryPublisher publisher;

	private final StoryReviewRepository reviews;

	/** 신청·승인 시각은 여기서만 계산한다 (§13-57, #290). 회차를 가르는 규칙이 하나여야 한다. */
	private final StoryReviewTimeline timeline;

	private final UgcLimitProperties limits;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public SubmissionService(DraftService drafts, PrecheckScreen screen, StoryPublisher publisher,
			StoryReviewRepository reviews, StoryReviewTimeline timeline, UgcLimitProperties limits,
			Clock clock, PlatformTransactionManager catalogTransactionManager) {
		this.drafts = drafts;
		this.screen = screen;
		this.publisher = publisher;
		this.reviews = reviews;
		this.timeline = timeline;
		this.limits = limits;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 제출한다.
	 *
	 * <p><b>검수가 먼저이고 발행이 나중이다.</b> 순서를 뒤집으면 반려된 원고의 작품이 카탈로그에
	 * 남는다 — 아무도 볼 수 없더라도 그것은 쌓인다.
	 */
	public SubmissionOutcome submit(UUID authorRef, UUID draftId, Visibility visibility) {
		StoryDraft draft = this.drafts.read(authorRef, draftId);
		// #326 — 작품 정의와 상태 스키마가 함께 나온다. 조건이 보는 이름과 화이트리스트에
		// 선언되는 이름은 같은 목록이어야 한다.
		DraftStoryDefinition.Publishable publishable = DraftStoryDefinition.from(authorRef,
				draft.getPayload());
		StoryDefinition definition = publishable.definition();

		PrecheckScreen.Result screened = this.screen
				.screen(fieldsOf(definition, publishable.stateSchema().flags()));
		if (screened.state() == com.neowadaeum.authoring.draft.DraftSafetyState.BLOCKED) {
			return rejected(screened.findings());
		}

		// §13-58 — 원고가 기억하는 작품이 이미 없을 수 있다. 작성자가 지웠거나(#290-3), 미리보기가
		// 만든 작품을 파기가 가져갔을 때다(B-61). 그 원고의 제출은 **재제출이 아니라 새 제출**이다 —
		// 없는 작품에 버전을 얹으면 지운 작품이 되살아나거나(그쪽은 applyReview 가 막는다) 아무도
		// 가리키지 않는 버전만 쌓인다. 원고는 지우지 않았으므로 작성자가 잃는 것은 없다.
		UUID existingStoryId = (draft.getStoryId() != null
				&& this.publisher.statusOf(draft.getStoryId()).isPresent()) ? draft.getStoryId() : null;

		// R8.12 — 새 작품일 때만 개수를 본다 (B-60). 재제출은 같은 작품에 버전을 얹으므로
		// 개수를 늘리지 않는다 (B-56) — 거기서 막으면 상한에 닿은 작성자가 **이미 낸 작품조차
		// 고치지 못하게** 된다.
		if (existingStoryId == null) {
			requireStoryQuota(authorRef);
		}

		// R8.8 — 재제출은 같은 작품에 새 버전을 얹는다. 원고가 자기 작품을 기억한다 (B-56).
		SubmissionOutcome outcome = approve(existingStoryId, definition,
				publishable.stateSchema().toJson(), visibility);
		this.drafts.linkStory(authorRef, draftId, outcome.storyId());
		return outcome;
	}

	/**
	 * 검수 상태를 다시 본다.
	 *
	 * <p><b>제출한 적이 없으면 {@code draft} 다</b> — 없는 것이 아니라 아직 내지 않은 것이며,
	 * 404 로 답하면 작성자는 원고가 사라졌다고 읽는다.
	 *
	 * <p><b>반려 사유를 다시 계산하지 않는다.</b> 마지막 검수 이력에 담긴 것을 그대로 준다 —
	 * 다시 검사하면 그 사이 블록리스트가 바뀌었을 때 <b>작성자가 보는 이유가 달라진다.</b>
	 */
	public SubmissionOutcome reviewStatus(UUID authorRef, UUID draftId) {
		StoryDraft draft = this.drafts.read(authorRef, draftId);
		// §13-58 — 원고가 기억하는 작품이 지워졌으면 낸 적 없는 원고와 같다. 여기서 404 를 내면
		// **원고가 사라졌다**고 읽히는데, 지운 것은 작품이지 원고가 아니다 (submit 도 같은 판단).
		if (draft.getStoryId() == null || this.publisher.statusOf(draft.getStoryId()).isEmpty()) {
			return new SubmissionOutcome(null, ReviewStatus.DRAFT, Visibility.PRIVATE, List.of(),
					StoryReviewTimes.NONE);
		}
		return this.transactions.execute(status -> {
			StoryPublisher.StoryStatus stored = this.publisher.statusOf(draft.getStoryId())
					.orElseThrow(() -> new com.neowadaeum.common.error.ApiException(
							com.neowadaeum.common.error.ErrorCode.NOT_FOUND));
			List<String> reasons = this.reviews
					.findFirstByStoryIdOrderByReviewedAtDesc(draft.getStoryId())
					.map(review -> reasonsOf(review.getReasons())).orElse(List.of());
			return new SubmissionOutcome(draft.getStoryId(),
					ReviewStatus.valueOf(stored.reviewStatus().toUpperCase(java.util.Locale.ROOT)),
					Visibility.valueOf(stored.visibility().toUpperCase(java.util.Locale.ROOT)),
					reasons, this.timeline.of(draft.getStoryId()));
		});
	}

	/**
	 * <b>한 계정이 플랫폼 비용을 정하지 않는다</b> (R8.12).
	 *
	 * <p>작품 하나는 발행된 버전과 챕터·엔딩을 갖고, 검수와 사후 관리(B-59)의 대상이 된다 —
	 * 만드는 쪽에 상한이 없으면 <b>그 뒤의 모든 비용에도 상한이 없다.</b>
	 *
	 * <p><b>{@code 403} 이다.</b> 날이 바뀌어도 늘지 않으므로 기다리라고 안내하면 기다린 만큼
	 * 헛되다.
	 */
	private void requireStoryQuota(UUID authorRef) {
		if (this.publisher.countSubmittedStoriesOf(authorRef) >= this.limits.storiesPerAuthor()) {
			throw new com.neowadaeum.common.error.ApiException(
					com.neowadaeum.common.error.ErrorCode.STORY_LIMIT_REACHED);
		}
	}

	/** 저장된 것은 카테고리 배열이다 (R8.7). 파싱기를 들이지 않고 그대로 읽는다. */
	private static List<String> reasonsOf(String reasonsJson) {
		List<String> reasons = new java.util.ArrayList<>();
		for (var node : JSON.readTree(reasonsJson)) {
			reasons.add(node.asString());
		}
		return List.copyOf(reasons);
	}

	/**
	 * <b>전 필드와 챕터·엔딩을 함께 본다</b> (R8.5).
	 *
	 * <p>미리보기 3턴 출력은 여기서 다시 보지 않는다 (§13-38) — 그것은 이미 L2 를 지났다.
	 *
	 * <p><b>서버가 스스로 목록을 만드는 자리는 여기뿐이다</b> (§13-75). L0 는 <b>화면이 넘긴
	 * 필드 지도</b>를 검사하므로 화면이 안 넘긴 칸은 L0 도 지나지 않는다 — R8.3 이 <i>클라이언트
	 * 검증에만 의존하지 않는다</i> 를 세운 이유가 그것이다.
	 *
	 * <p><b>목록은 손으로 적는다</b> (§13-75). 프롬프트 레이어에서 파생시키지 않는 것은 검수
	 * 대상이 레이어보다 넓기 때문이다 — 소개글은 모델에게 가지 않지만 <b>타인에게 보인다</b>.
	 * 대신 새 값이 조용히 빠지지 못하게 {@code SubmissionFieldCoverageTests} 가 정의의 텍스트
	 * 성분을 전부 세고, 여기 걸리지도 제외되지도 않은 것을 <b>실패</b>로 만든다.
	 *
	 * <p><b>경로는 작성 화면의 이름이다</b> ({@code characters[0].persona}) — 밑줄을 그을 자리를
	 * 가리키는 값이므로 발행물의 이름({@code personaPrompt})이 아니라 원고 계약의 이름을 쓴다.
	 *
	 * @param flags 원고가 선언한 플래그 이름 (#362). <b>정의에는 없고 원고에만 있다</b> —
	 *     화이트리스트로 발행될 뿐이지만 매 턴 {@code GAME_STATE} 로 나가는 작성자 입력이다
	 */
	static Map<String, String> fieldsOf(StoryDefinition definition, Set<String> flags) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("title", definition.title());
		fields.put("shortDesc", definition.shortDesc());
		fields.put("worldIntro", definition.worldIntro());
		fields.put("worldPrompt", definition.worldPrompt());
		definition.chapters().forEach(chapter -> {
			fields.put("chapters[%d].title".formatted(chapter.chapterNo()), chapter.title());
			fields.put("chapters[%d].summarySeed".formatted(chapter.chapterNo()),
					chapter.summarySeed());
		});
		definition.endings().forEach(ending -> {
			fields.put("endings[%d].label".formatted(ending.endingNo()), ending.label());
			fields.put("endings[%d].epilogueText".formatted(ending.endingNo()),
					ending.epilogueText());
		});
		putCharacters(fields, definition.characters());
		// 플래그는 이름뿐이고 문장이 아니다. 짧다는 이유로 다르게 보지 않는다 (§13-75) —
		// 판정이 둘이 되면 무른 쪽이 곧 길이 된다.
		int flagIndex = 0;
		for (String flag : flags) {
			fields.put("flags[%d]".formatted(flagIndex++), flag);
		}
		fields.values().removeIf(java.util.Objects::isNull);
		return fields;
	}

	/**
	 * 인물도 작성자가 쓴 값이다 (R8.5, §13-75).
	 *
	 * <p><b>{@code persona} 는 매 턴 모델에게 들어간다</b> ({@code PromptAssembler} 의 인물
	 * 레이어), {@code name} 과 {@code oneLine} 은 <b>타인의 상세 화면에 뜬다</b> (I-8). 셋 다
	 * 검수 없이 나갈 자리가 아니다.
	 *
	 * <p><b>자리는 배열의 순서다</b> — 화면이 그 순서로 줄을 그리고 계약도 그 표기로 답한다
	 * ({@code characters[0].name}). 이름이 빈 줄은 인물이 아니므로 (§13-71) 발행 목록의 자리를
	 * 쓴다.
	 *
	 * <p><b>페르소나가 비어 있으면 한 줄 소개가 그 자리에 발행된다</b> (§13-71). 그때 같은
	 * 문장을 두 자리에 걸지 않는다 — 작성자가 <b>비어 있는 칸</b>에 밑줄을 보게 된다.
	 */
	private static void putCharacters(Map<String, String> fields,
			List<StoryDefinition.Character> characters) {
		for (int index = 0; index < characters.size(); index++) {
			StoryDefinition.Character character = characters.get(index);
			fields.put("characters[%d].name".formatted(index), character.name());
			fields.put("characters[%d].oneLine".formatted(index), character.oneLine());
			if (!java.util.Objects.equals(character.personaPrompt(), character.oneLine())) {
				fields.put("characters[%d].persona".formatted(index), character.personaPrompt());
			}
		}
	}

	/** <b>반려된 원고는 작품을 만들지 않는다.</b> 아무도 볼 수 없더라도 그것은 쌓인다. */
	private SubmissionOutcome rejected(List<PrecheckFinding> findings) {
		Set<String> reasons = new LinkedHashSet<>();
		findings.forEach(finding -> reasons.add(finding.kind()));
		// 작품이 만들어지지 않았으므로 검수 이력도 없다. 두 시각 모두 null 이다 (§13-57).
		return new SubmissionOutcome(null, ReviewStatus.REJECTED, Visibility.PRIVATE,
				List.copyOf(reasons), StoryReviewTimes.NONE);
	}

	/**
	 * 자동 검수를 통과했다.
	 *
	 * <p><b>{@code public} 은 여기서 열리지 않는다</b> (R8.6) — {@code in_review} 로 두고 사람을
	 * 기다린다. 그동안 <b>아무도 그 작품을 볼 수 없다</b>: R2.3 의 타인 조회 조건이
	 * {@code approved} <b>AND</b> {@code visibility <> private} 이므로 {@code in_review} 하나로
	 * 이미 가려진다.
	 *
	 * <p><b>이미 승인돼 있던 작품의 가시성은 지우지 않는다</b> (#249, §13-50) — 지우면 개정판이
	 * 반려됐을 때 <b>돌아갈 자리가 없다.</b>
	 *
	 * <p><b>재제출은 작품을 늘리지 않는다</b> (R8.8, B-56). 이미 낸 적이 있으면 같은 작품에 새
	 * 버전을 얹는다 — 새 작품을 만들면 <b>같은 이야기가 라이브러리에 둘</b>이 되고, 도달률은
	 * {@code (story_id, ending_no)} 로 집계되므로 <b>통계가 갈라진다.</b>
	 *
	 * <p><b>진행 중 세션은 흔들리지 않는다</b> (R2.1, §10.1-12). 새 버전은 승인 전까지 현재가
	 * 되지 않고, 현재가 된 뒤에도 이미 고정된 세션은 옛 버전을 계속 본다 (I-4).
	 */
	private SubmissionOutcome approve(UUID existingStoryId, StoryDefinition definition,
			String stateSchema, Visibility visibility) {
		boolean needsHuman = visibility == Visibility.PUBLIC;
		ReviewStatus status = needsHuman ? ReviewStatus.IN_REVIEW : ReviewStatus.APPROVED;

		return this.transactions.execute(status2 -> {
			Visibility effective = needsHuman ? keptVisibilityOf(existingStoryId) : visibility;
			StoryPublisher.PublishedVersion published = (existingStoryId == null)
					? this.publisher.publishNew(definition, stateSchema)
					: this.publisher.publishRevision(existingStoryId, definition, stateSchema);
			this.publisher.applyReview(published.storyId(), status.columnValue(),
					effective.columnValue());
			if (!needsHuman) {
				// R8.8 — 승인이 곧 게시다. 인간 검수가 남았으면 아직 현재 버전이 아니다.
				this.publisher.markCurrent(published.storyId(), published.versionId());
			}
			this.reviews.save(StoryReview.of(published.storyId(), ReviewStage.AUTO,
					ReviewVerdict.PASS, "[]", null, null, Instant.now(this.clock)));
			// 방금 남긴 기록이 이 회차의 시작이다. 시각을 여기서 따로 짓지 않고 이력에서 읽는다
			// — 응답과 목록이 같은 규칙을 쓰지 않으면 두 화면이 다른 날짜를 말한다 (§13-57).
			return new SubmissionOutcome(published.storyId(), status, effective, List.of(),
					this.timeline.of(published.storyId()));
		});
	}

	/**
	 * 검수를 기다리는 동안 남겨 둘 가시성 (#249).
	 *
	 * <p><b>이미 승인돼 있던 작품의 가시성을 지우지 않는다.</b> 재검수 동안 작품이 목록에서
	 * 내려가는 것은 {@code review_status} 하나로 이미 성립한다 — R2.3 의 타인 조회 조건이
	 * {@code approved} <b>AND</b> {@code visibility <> private} 이기 때문이다 (§13-40 이 받아들인
	 * 대가). 여기서 {@code private} 으로 함께 내리면 <b>돌아갈 자리가 사라지고</b>, 개정판이
	 * 반려됐을 때 작성자는 <b>고치기 전에 갖고 있던 게시까지</b> 잃는다.
	 *
	 * <p><b>"있던 자리"는 승인돼 있던 자리다.</b> 정지·반려 상태에서 낸 재제출은 그 취급을
	 * 받지 않는다 — 그것까지 되돌리면 <b>반려가 정지를 푸는 길</b>이 된다.
	 *
	 * <p>처음 내는 작품은 {@code private} 이다. 아무에게도 보인 적이 없으므로 남겨 둘 자리가
	 * 없다 (R8.6 — {@code public} 은 사람이 연다).
	 */
	private Visibility keptVisibilityOf(UUID existingStoryId) {
		if (existingStoryId == null) {
			return Visibility.PRIVATE;
		}
		return this.publisher.statusOf(existingStoryId)
				.filter(stored -> ReviewStatus.APPROVED.columnValue().equals(stored.reviewStatus()))
				.map(stored -> Visibility.valueOf(stored.visibility().toUpperCase(java.util.Locale.ROOT)))
				.orElse(Visibility.PRIVATE);
	}

	/**
	 * 제출 결과.
	 *
	 * <p><b>비율도 임계값도 담지 않는다</b> (§13-12, S-11) — 값을 알면 그 아래로 관리할 수 있다.
	 */
	public record SubmissionOutcome(UUID storyId, ReviewStatus reviewStatus, Visibility visibility,
			List<String> rejectReasons, StoryReviewTimes times) {
	}
}
