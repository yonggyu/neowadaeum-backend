package com.neowadaeum.authoring.review;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.catalog.query.StoryVersionView;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.authoring.draft.StoryDraft;
import com.neowadaeum.authoring.draft.StoryDraftRepository;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AccessAuditRecorder;
import com.neowadaeum.common.spi.AuditedResource;
import com.neowadaeum.common.spi.AuthorDisplayNameQuery;
import com.neowadaeum.common.spi.PreviewTurnsQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검수자가 원고를 여는 문 (#316, §13-61).
 *
 * <p><b>큐가 답하지 않는 것을 답한다.</b> 큐는 <b>무엇을 볼 차례인가</b>이고 (B-55), 이쪽은
 * <b>무엇을 보고 판정하는가</b>다 — 그 문이 없으면 검수자는 제목과 상태만 보고 승인/반려하게
 * 된다.
 *
 * <p><b>열람은 기록에 남는다</b> (R12.3, S-5). {@code ReviewQueueItem} 이 원고 본문을 담지
 * 않으면서 <b>"원문 열람은 감사가 걸린 다른 문"</b>이라고 적은 그 문이 여기다 — 기록에 실패하면
 * 원문은 나가지 않는다 ({@link AccessAuditRecorder}).
 *
 * <p><b>기록이 원문보다 먼저다.</b> 순서를 뒤집으면 <b>읽고 나서 기록에 실패한</b> 열람이 생기고,
 * 그것이 곧 기록되지 않는 열람 경로다.
 *
 * <p><b>없는 작품에 대한 열람은 남기지 않는다</b> — 작품을 먼저 찾는다. 순서를 뒤집으면 감사
 * 로그가 존재하지 않는 작품으로 채워진다 ({@code AdminDebugController} 와 같은 이유다).
 */
@Service
public class ReviewManuscriptService {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final StoryPublisher publisher;

	private final StoryVersionFacade versions;

	private final StoryReviewRepository reviews;

	private final AuthorDisplayNameQuery authors;

	private final AccessAuditRecorder access;

	/** 미리보기 세션을 기억하는 것은 원고다 (#332). 작품에서 그 세션으로 가는 길이 이것뿐이다. */
	private final StoryDraftRepository drafts;

	private final PreviewTurnsQuery previewTurns;

	public ReviewManuscriptService(StoryPublisher publisher, StoryVersionFacade versions,
			StoryReviewRepository reviews, AuthorDisplayNameQuery authors,
			AccessAuditRecorder access, StoryDraftRepository drafts,
			PreviewTurnsQuery previewTurns) {
		this.publisher = publisher;
		this.versions = versions;
		this.reviews = reviews;
		this.authors = authors;
		this.access = access;
		this.drafts = drafts;
		this.previewTurns = previewTurns;
	}

	/**
	 * 원고를 연다.
	 *
	 * <p><b>가장 마지막 버전을 본다</b> (R8.8). 검수를 기다리는 동안 작성자가 새 버전을 얹었다면
	 * 사람이 볼 것도 승인이 여는 것도 그 최신본이어야 한다 — 판정과 열람이 다른 버전을 보면
	 * <b>승인된 적 없는 원고가 열린다.</b>
	 *
	 * @param adminUserId 읽는 사람. <b>{@code playerRef} 가 아니다</b> — 감사는 사람을 가리킨다
	 * @throws ApiException {@code NOT_FOUND} — 없는 작품이거나 발행된 버전이 없는 작품
	 */
	public ReviewManuscript read(UUID adminUserId, UUID storyId) {
		StoryPublisher.StoryHeader header = this.publisher.headerOf(storyId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID versionId = this.publisher.latestVersionId(storyId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

		this.access.record(adminUserId, AuditedResource.STORY_DRAFT, storyId);

		StoryVersionView version = this.versions.findByVersionId(versionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Optional<StoryDraft> draft = this.drafts.findFirstByStoryIdOrderByUpdatedAtDesc(storyId);
		return new ReviewManuscript(storyId, header.title(), header.shortDesc(), header.worldIntro(),
				header.reviewStatus(), header.visibility(), header.createdAt(),
				displayNameOf(header.authorRef()), version.worldPrompt(), charactersOf(version),
				chaptersOf(version), endingsOf(versionId, version), autoCheckOf(storyId),
				draft.map(StoryDraft::getPreviewedAt).orElse(null), previewTurnsOf(draft));
	}

	/**
	 * 미리보기가 실제로 내놓은 문장 (#332, §13-68).
	 *
	 * <p><b>비어 있는 것이 실패가 아니다.</b> 미리보기를 돌리지 않았거나 그것이 보관 기간을
	 * 넘겨 파기되었다 (§13-37) — 어느 쪽이든 없다는 것이 사실이고, 여기서 404 를 내면
	 * <b>검수 상세 전체가 열리지 않는다.</b>
	 */
	private List<ReviewManuscript.PreviewTurn> previewTurnsOf(Optional<StoryDraft> draft) {
		UUID sessionId = draft.map(StoryDraft::getPreviewSessionId).orElse(null);
		List<ReviewManuscript.PreviewTurn> turns = new ArrayList<>();
		for (PreviewTurnsQuery.PreviewTurn turn : this.previewTurns.findBySession(sessionId)) {
			turns.add(new ReviewManuscript.PreviewTurn(turn.turnNo(), turn.chapterNo(),
					turn.speakerName(), turn.paragraphs(), turn.choices(), turn.createdAt()));
		}
		return turns;
	}

	/** <b>표시명만이다</b> (§13-7, I-3). 설정하지 않은 작성자는 이름이 없는 것이 사실이다. */
	private String displayNameOf(UUID authorRef) {
		return (authorRef == null) ? null : this.authors.findDisplayName(authorRef).orElse(null);
	}

	private static List<ReviewManuscript.ManuscriptCharacter> charactersOf(StoryVersionView version) {
		List<ReviewManuscript.ManuscriptCharacter> characters = new ArrayList<>();
		for (StoryVersionView.CharacterView character : version.characters()) {
			characters.add(new ReviewManuscript.ManuscriptCharacter(character.name(),
					character.persona()));
		}
		return characters;
	}

	private static List<ReviewManuscript.ManuscriptChapter> chaptersOf(StoryVersionView version) {
		List<ReviewManuscript.ManuscriptChapter> chapters = new ArrayList<>();
		for (StoryVersionView.ChapterView chapter : version.chapters()) {
			chapters.add(new ReviewManuscript.ManuscriptChapter(chapter.chapterNo(), chapter.title(),
					chapter.minTurns(), chapter.maxTurns()));
		}
		return chapters;
	}

	/** 에필로그 원문은 버전 조회에 없다 — 검수에서만 필요한 값이므로 따로 읽는다. */
	private List<ReviewManuscript.ManuscriptEnding> endingsOf(UUID versionId,
			StoryVersionView version) {
		Map<Integer, String> epilogues = this.publisher.epiloguesOf(versionId);
		List<ReviewManuscript.ManuscriptEnding> endings = new ArrayList<>();
		for (StoryVersionView.EndingView ending : version.endings()) {
			endings.add(new ReviewManuscript.ManuscriptEnding(ending.endingNo(), ending.label(),
					epilogues.getOrDefault(ending.endingNo(), ""), ending.secret(),
					ending.defaultEnding()));
		}
		return endings;
	}

	/**
	 * 자동 검수가 무엇을 봤는가 (R8.5, R8.7).
	 *
	 * <p><b>사람의 판정 이력은 담지 않는다.</b> 지난 판정을 돌려주는 것은 처리 이력의 일이고,
	 * 여기가 답하는 것은 <b>지금 이 원고에 대해 자동 단계가 무엇을 말했는가</b>다.
	 */
	private ReviewManuscript.AutoCheckSummary autoCheckOf(UUID storyId) {
		Optional<StoryReview> auto = this.reviews.findByStoryIdOrderByReviewedAtDesc(storyId).stream()
				.filter(review -> review.getStage() == ReviewStage.AUTO).findFirst();
		return auto.map(review -> new ReviewManuscript.AutoCheckSummary(
				review.getVerdict().columnValue(), categoriesOf(review.getReasons()),
				review.getReviewedAt())).orElse(null);
	}

	/** 저장된 것은 카테고리 배열이다 (R8.7). 항목이 아니라 분류만 나간다 (S-11). */
	private static List<String> categoriesOf(String reasonsJson) {
		List<String> categories = new ArrayList<>();
		for (var node : JSON.readTree(reasonsJson)) {
			categories.add(node.asString());
		}
		return categories;
	}
}
