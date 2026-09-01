package com.neowadaeum.authoring.review;

import com.neowadaeum.authoring.draft.DraftSafetyState;
import com.neowadaeum.authoring.precheck.PrecheckFinding;
import com.neowadaeum.authoring.precheck.PrecheckScreen;
import com.neowadaeum.catalog.publish.PublishedStoryTexts;
import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.spi.UgcRescan;
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
 * 승인작 재스캔 (§8.4, R9.4, B-59).
 *
 * <p><b>블록리스트는 운영 중에 늘어난다.</b> 어제 통과한 작품이 오늘의 목록으로는 걸리며,
 * 갱신이 앞으로 만들어질 것에만 적용되면 <b>이미 게시된 것은 영원히 옛 기준</b>이다.
 *
 * <p><b>같은 L1 을 쓴다</b> (B-50 의 {@link PrecheckScreen}). 재스캔이 다른 눈으로 보면
 * 작성 중 피드백과 제출 검수가 통과시킨 것을 사후에 뒤집게 되고, 그러면 <b>어느 판정이
 * 진실인지</b>가 매번 문제가 된다.
 *
 * <p><b>걸린 작품은 내려간다.</b> 신고 누적과 같은 자리다 (R8.9, B-57) — 자동으로 내리되
 * <b>자동으로 올리지 않는다.</b> 검수 큐에서 사람이 판정한다.
 *
 * <p><b>이력을 남긴다</b> (§2.4). 왜 내려갔는지가 없으면 작성자에게도 검수자에게도 답할
 * 것이 없다. 담는 것은 <b>카테고리뿐이다</b> (R8.7) — 어떤 항목에 걸렸는지를 담으면 그 표가
 * 우회 사전이 된다 (S-11).
 */
@Service
public class UgcRescanner implements UgcRescan {

	/**
	 * 한 회차가 보는 작품 수.
	 *
	 * <p>전부를 한 번에 보지 않는 이유는 <b>한 회차가 길어지면 그 사이의 갱신이 다음 회차로
	 * 밀리기 때문</b>이다. 남은 것은 다음 회차가 본다 — 재스캔은 누적이 아니라 다시 보는 일이다.
	 */
	private static final int PAGE_SIZE = 100;

	private static final int MAX_PAGES = 20;

	private static final String SUSPENDED_REASON_SOURCE = "rescan";

	private final PublishedStoryTexts published;

	private final PrecheckScreen screen;

	private final StoryPublisher publisher;

	private final StoryReviewRepository reviews;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public UgcRescanner(PublishedStoryTexts published, PrecheckScreen screen, StoryPublisher publisher,
			StoryReviewRepository reviews, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.published = published;
		this.screen = screen;
		this.publisher = publisher;
		this.reviews = reviews;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 한 차례 돈다.
	 *
	 * <p><b>작품마다 트랜잭션을 나눈다.</b> 한 트랜잭션으로 묶으면 마지막 작품에서 실패했을 때
	 * 앞의 판정이 전부 사라지고, 그러면 <b>한 회차가 통째로 없던 일</b>이 된다.
	 */
	@Override
	public int rescan() {
		int suspended = 0;
		UUID after = null;

		for (int page = 0; page < MAX_PAGES; page++) {
			List<PublishedStoryTexts.ApprovedStory> stories = this.published.approvedPage(PAGE_SIZE,
					after);
			if (stories.isEmpty()) {
				break;
			}
			for (PublishedStoryTexts.ApprovedStory story : stories) {
				if (suspendIfViolating(story)) {
					suspended++;
				}
			}
			after = stories.get(stories.size() - 1).storyId();
		}
		return suspended;
	}

	/**
	 * 걸리면 내린다.
	 *
	 * <p><b>내려가지 않은 작품에는 이력을 남기지 않는다.</b> 통과는 사실이지만 회차마다 한 줄씩
	 * 쌓으면 이력이 <b>배치 실행 기록</b>이 되고, 작성자와 검수자가 보는 "왜 그렇게 됐는지"가
	 * 그 사이에 묻힌다.
	 */
	private boolean suspendIfViolating(PublishedStoryTexts.ApprovedStory story) {
		PrecheckScreen.Result screened = this.screen.screen(fieldsOf(story));
		if (screened.state() != DraftSafetyState.BLOCKED) {
			return false;
		}
		return Boolean.TRUE.equals(this.transactions.execute(status -> {
			if (!this.publisher.suspend(story.storyId())) {
				// 그 사이에 다른 경로가 내렸다 (신고 누적 등). 두 번 세지 않는다.
				return false;
			}
			this.reviews.save(StoryReview.of(story.storyId(), ReviewStage.AUTO, ReviewVerdict.REJECT,
					reasonsJson(screened.findings()), null, SUSPENDED_REASON_SOURCE,
					Instant.now(this.clock)));
			return true;
		}));
	}

	/**
	 * {@link PrecheckScreen} 은 필드 경로를 요구한다 — 밑줄을 그을 자리를 알려 주기 위해서다.
	 *
	 * <p><b>여기서 그 자리는 쓰이지 않는다.</b> 재스캔이 남기는 것은 카테고리뿐이므로 (R8.7)
	 * 번호만 붙인다. 작성 화면의 경로 표기를 흉내 내면 <b>쓰이지 않는 규약이 하나 더</b> 생긴다.
	 */
	private static Map<String, String> fieldsOf(PublishedStoryTexts.ApprovedStory story) {
		Map<String, String> fields = new LinkedHashMap<>();
		List<String> texts = story.texts();
		for (int i = 0; i < texts.size(); i++) {
			fields.put(Integer.toString(i), texts.get(i));
		}
		return fields;
	}

	/** 카테고리만 담는다 (R8.7, S-11). 중복은 접는다 — 몇 번 걸렸는지는 사유가 아니다. */
	private static String reasonsJson(List<PrecheckFinding> findings) {
		Set<String> categories = new LinkedHashSet<>();
		findings.forEach(finding -> categories.add(finding.kind()));
		return categories.stream().collect(java.util.stream.Collectors.joining("\",\"", "[\"", "\"]"));
	}
}
