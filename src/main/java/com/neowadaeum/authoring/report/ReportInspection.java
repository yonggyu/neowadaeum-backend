package com.neowadaeum.authoring.report;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검수자가 <b>무엇이 신고됐는지</b>를 읽는다 (§14, R8.9, §13-62, 이슈 #316).
 *
 * <p><b>별도의 큐가 아니다.</b> 임계에 닿은 작품은 이미 같은 인간 검수 큐에 {@code suspended}
 * 로 올라와 있고 (§13-41, B-57), 없던 것은 <b>그 안에서 무엇이 신고됐는가</b>였다.
 *
 * <p><b>두 가지를 함께 답한다.</b> 사유별 집계는 <b>무엇이 몇 건인지</b>를, 개별 목록은
 * <b>언제 어떤 턴이 걸렸는지</b>를 말한다 — 검수자가 먼저 보는 것은 앞쪽이고, 뒤쪽은 그것을
 * 되짚는 재료다.
 *
 * <p><b>{@code reporter_ref} 와 {@code detail} 은 나가지 않는다</b> (I-3, §13-62). 누가
 * 신고했는지는 판정에 쓰이지 않으며, 쓰이지 않는 식별자는 <b>담을 이유가 없다.</b> 신고자가 쓴
 * 자유 문장도 같다 — 그 안에는 신고자를 특정하는 말과 걸린 표현이 그대로 들어 있다 (S-11).
 *
 * <p><b>{@link ReportService} 와 나눈 것은 의도다.</b> 그쪽은 접수와 자동 정지를 쓰고, 이쪽은
 * 읽기만 한다 — 한 클래스가 둘을 겸하면 읽기 경로가 쓰기 트랜잭션을 물고 다닌다.
 */
@Service
public class ReportInspection {

	/**
	 * 한 번에 보는 개별 신고 수.
	 *
	 * <p>판정에 필요한 것은 <b>무엇이 몇 건인지</b>이고 그 답은 집계가 전건으로 준다. 목록은
	 * 최근 것부터 이만큼이며, 다 읽어야 판정할 수 있는 화면이라면 그것은 이미 집계의 일이다.
	 */
	private static final int PAGE_SIZE = 50;

	private final ContentReportRepository reports;

	private final StoryPublisher publisher;

	public ReportInspection(ContentReportRepository reports, StoryPublisher publisher) {
		this.reports = reports;
		this.publisher = publisher;
	}

	/**
	 * 이 작품에 달린 신고.
	 *
	 * <p><b>작품 신고만 온다</b> (§13-41). 턴 신고에서 작품을 알아내려면 {@code authoring} 이
	 * {@code play} 의 세션 표를 읽어야 하고, 스토어가 다르며 (§5.3) 그 참조는 애플리케이션
	 * 레벨에서도 열려 있지 않다 — <b>턴 신고는 기록되지만 여기에 합산되지 않는다.</b> 정지
	 * 임계가 세는 것과 검수자가 보는 것이 같아야, 왜 내려갔는지가 화면에서 설명된다.
	 *
	 * @throws ApiException {@code NOT_FOUND} — 없는 작품. 빈 목록으로 답하면 <b>오타 난 id 가
	 *     "신고 없음"으로 보인다</b>
	 */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	public StoryReports forStory(UUID storyId) {
		if (this.publisher.statusOf(storyId).isEmpty()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		String targetType = ReportTarget.STORY.columnValue();

		List<ReasonCount> counts = new ArrayList<>();
		for (ContentReportRepository.ReasonTally tally : this.reports.tallyByReason(targetType, storyId)) {
			counts.add(new ReasonCount(
					ReportReason.valueOf(tally.getReason().toUpperCase(Locale.ROOT)),
					tally.getReportCount()));
		}

		List<Item> items = this.reports
				.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, storyId, Limit.of(PAGE_SIZE))
				.stream()
				.map(report -> new Item(report.getId(), report.getReason(), report.getTurnNo(),
						report.getCreatedAt(), report.getStatus()))
				.toList();

		return new StoryReports(storyId, List.copyOf(counts), items);
	}

	/**
	 * 한 작품의 신고.
	 *
	 * @param reasonCounts 사유별 <b>전건</b> 집계
	 * @param items 최근 신고. 상한이 있다
	 */
	public record StoryReports(UUID storyId, List<ReasonCount> reasonCounts, List<Item> items) {
	}

	/** 사유 하나에 몇 건. */
	public record ReasonCount(ReportReason reason, long count) {
	}

	/**
	 * 개별 신고 한 건.
	 *
	 * <p><b>신고자가 없다.</b> 이 record 에 그 자리를 만들지 않는 것이 I-3 의 보장이며,
	 * {@link ContentReport} 에 {@code reporterRef} 게터가 없는 것과 같은 이유다.
	 *
	 * @param turnNo 신고된 턴. <b>작품 신고에는 없다</b> — {@code null} 이 정상이다
	 */
	public record Item(UUID reportId, ReportReason reason, Integer turnNo, Instant createdAt,
			ContentReport.ReportStatus status) {
	}
}
