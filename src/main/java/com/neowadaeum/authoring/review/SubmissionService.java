package com.neowadaeum.authoring.review;

import com.neowadaeum.authoring.UgcLimitProperties;
import com.neowadaeum.authoring.draft.DraftService;
import com.neowadaeum.authoring.draft.DraftStoryDefinition;
import com.neowadaeum.authoring.draft.StoryDraft;
import com.neowadaeum.authoring.precheck.PrecheckFinding;
import com.neowadaeum.authoring.precheck.PrecheckScreen;
import com.neowadaeum.catalog.publish.StoryDefinition;
import com.neowadaeum.catalog.publish.StoryPublisher;
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

	/** 상태 스키마. 작성자가 고른 템플릿이 채우기 전까지는 비어 있다 (R4.4). */
	private static final String STATE_SCHEMA = "{\"flags\":[]}";

	private static final tools.jackson.databind.json.JsonMapper JSON =
			tools.jackson.databind.json.JsonMapper.builder().build();

	private final DraftService drafts;

	private final PrecheckScreen screen;

	private final StoryPublisher publisher;

	private final StoryReviewRepository reviews;

	private final UgcLimitProperties limits;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public SubmissionService(DraftService drafts, PrecheckScreen screen, StoryPublisher publisher,
			StoryReviewRepository reviews, UgcLimitProperties limits, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.drafts = drafts;
		this.screen = screen;
		this.publisher = publisher;
		this.reviews = reviews;
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
		StoryDefinition definition = DraftStoryDefinition.from(authorRef, draft.getPayload());

		PrecheckScreen.Result screened = this.screen.screen(fieldsOf(definition));
		if (screened.state() == com.neowadaeum.authoring.draft.DraftSafetyState.BLOCKED) {
			return rejected(screened.findings());
		}

		// R8.12 — 새 작품일 때만 개수를 본다 (B-60). 재제출은 같은 작품에 버전을 얹으므로
		// 개수를 늘리지 않는다 (B-56) — 거기서 막으면 상한에 닿은 작성자가 **이미 낸 작품조차
		// 고치지 못하게** 된다.
		if (draft.getStoryId() == null) {
			requireStoryQuota(authorRef);
		}

		// R8.8 — 재제출은 같은 작품에 새 버전을 얹는다. 원고가 자기 작품을 기억한다 (B-56).
		SubmissionOutcome outcome = approve(draft.getStoryId(), definition, visibility);
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
		if (draft.getStoryId() == null) {
			return new SubmissionOutcome(null, ReviewStatus.DRAFT, Visibility.PRIVATE, List.of());
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
					reasons);
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
	 */
	private static Map<String, String> fieldsOf(StoryDefinition definition) {
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
		fields.values().removeIf(java.util.Objects::isNull);
		return fields;
	}

	/** <b>반려된 원고는 작품을 만들지 않는다.</b> 아무도 볼 수 없더라도 그것은 쌓인다. */
	private SubmissionOutcome rejected(List<PrecheckFinding> findings) {
		Set<String> reasons = new LinkedHashSet<>();
		findings.forEach(finding -> reasons.add(finding.kind()));
		return new SubmissionOutcome(null, ReviewStatus.REJECTED, Visibility.PRIVATE,
				List.copyOf(reasons));
	}

	/**
	 * 자동 검수를 통과했다.
	 *
	 * <p><b>{@code public} 은 여기서 열리지 않는다</b> (R8.6) — {@code in_review} 로 두고 사람을
	 * 기다린다. 그동안 작품은 {@code private} 이다: <b>검수 중인 작품이 보이면 검수의 의미가 없다.</b>
	 *
	 * <p><b>재제출은 작품을 늘리지 않는다</b> (R8.8, B-56). 이미 낸 적이 있으면 같은 작품에 새
	 * 버전을 얹는다 — 새 작품을 만들면 <b>같은 이야기가 라이브러리에 둘</b>이 되고, 도달률은
	 * {@code (story_id, ending_no)} 로 집계되므로 <b>통계가 갈라진다.</b>
	 *
	 * <p><b>진행 중 세션은 흔들리지 않는다</b> (R2.1, §10.1-12). 새 버전은 승인 전까지 현재가
	 * 되지 않고, 현재가 된 뒤에도 이미 고정된 세션은 옛 버전을 계속 본다 (I-4).
	 */
	private SubmissionOutcome approve(UUID existingStoryId, StoryDefinition definition,
			Visibility visibility) {
		boolean needsHuman = visibility == Visibility.PUBLIC;
		ReviewStatus status = needsHuman ? ReviewStatus.IN_REVIEW : ReviewStatus.APPROVED;
		Visibility effective = needsHuman ? Visibility.PRIVATE : visibility;

		return this.transactions.execute(status2 -> {
			StoryPublisher.PublishedVersion published = (existingStoryId == null)
					? this.publisher.publishNew(definition, STATE_SCHEMA)
					: this.publisher.publishRevision(existingStoryId, definition, STATE_SCHEMA);
			this.publisher.applyReview(published.storyId(), status.columnValue(),
					effective.columnValue());
			if (!needsHuman) {
				// R8.8 — 승인이 곧 게시다. 인간 검수가 남았으면 아직 현재 버전이 아니다.
				this.publisher.markCurrent(published.storyId(), published.versionId());
			}
			this.reviews.save(StoryReview.of(published.storyId(), ReviewStage.AUTO,
					ReviewVerdict.PASS, "[]", null, null, Instant.now(this.clock)));
			return new SubmissionOutcome(published.storyId(), status, effective, List.of());
		});
	}

	/**
	 * 제출 결과.
	 *
	 * <p><b>비율도 임계값도 담지 않는다</b> (§13-12, S-11) — 값을 알면 그 아래로 관리할 수 있다.
	 */
	public record SubmissionOutcome(UUID storyId, ReviewStatus reviewStatus, Visibility visibility,
			List<String> rejectReasons) {
	}
}
