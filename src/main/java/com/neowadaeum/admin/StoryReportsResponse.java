package com.neowadaeum.admin;

import com.neowadaeum.authoring.report.ReportInspection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 이 작품에 무엇이 신고됐는가 (§14, §13-62, 이슈 #316).
 *
 * <p><b>둘을 함께 준다.</b> 화면 하나가 좌측에 사유별 집계를, 우측에 개별 신고를 그린다 —
 * 나누면 검수자가 두 번 요청해야 하고, 그 사이에 <b>집계와 목록이 다른 시점을 가리킨다.</b>
 *
 * <p><b>신고자가 없다</b> (I-3). {@code reporter_ref} 는 판정에 쓰이지 않으며, 쓰이지 않는
 * 식별자는 담을 이유가 없다 — 검수자가 <b>누가 신고했는지</b>를 알면 그것이 판정에 섞인다.
 * 검수 큐가 작성자를 담지 않는 것과 같은 이유다.
 *
 * <p><b>신고자가 쓴 자유 문장({@code detail})도 없다.</b> 그 안에는 신고자를 특정하는 말과
 * 걸린 표현이 그대로 들어 있고 (S-11), 판정에 먼저 필요한 것은 <b>무엇이 몇 건인지</b>다.
 * 문장은 표에 남아 사후 조사가 쓴다.
 *
 * @param reasonCounts 사유별 <b>전건</b> 집계. 검수자가 먼저 보는 숫자다
 * @param reports 최근 신고. 상한이 있다 — 전건은 위쪽이 답한다
 */
public record StoryReportsResponse(UUID storyId, List<ReasonCount> reasonCounts,
		List<ReportItem> reports) {

	static StoryReportsResponse of(ReportInspection.StoryReports found) {
		return new StoryReportsResponse(found.storyId(),
				found.reasonCounts().stream()
						.map(count -> new ReasonCount(count.reason().columnValue(), count.count()))
						.toList(),
				found.items().stream().map(ReportItem::of).toList());
	}

	/** 몇 건인지 세어 본 총합. <b>응답에 담지 않는다</b> — 감사 기록이 쓴다. */
	long total() {
		return this.reasonCounts.stream().mapToLong(ReasonCount::count).sum();
	}

	/** 사유 하나에 몇 건. 표기는 계약이 적은 소문자 그대로다 (§13.9). */
	public record ReasonCount(String reason, long count) {
	}

	/**
	 * 개별 신고 한 건.
	 *
	 * @param turnNo 신고된 턴. <b>작품 신고에는 없으므로 {@code null} 이 정상이다</b> — 키를
	 *     생략하지 않고 {@code null} 로 명시한다
	 */
	public record ReportItem(UUID reportId, String reason, Integer turnNo, Instant createdAt,
			String status) {

		static ReportItem of(ReportInspection.Item item) {
			return new ReportItem(item.reportId(), item.reason().columnValue(), item.turnNo(),
					item.createdAt(), item.status().columnValue());
		}
	}
}
