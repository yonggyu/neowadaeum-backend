package com.neowadaeum.authoring.report;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 신고 접수 (§8.4, §13.9, 세이프티 L3).
 *
 * <p>세이프티 L0~L2 는 <b>만들어지는 시점</b>에 본다. 이미 게시된 작품에 대해서는 사람이
 * 알려 주는 길뿐이며, 그것이 이 자리다 (R13.5 — Play 화면 Menu 의 "신고"가 유일한 사용자
 * 경로다).
 *
 * <p><b>같은 사람이 같은 대상을 두 번 신고해도 한 건이다.</b> 누적이 자동 정지의 근거이므로
 * (R8.9) 중복이 세어지면 <b>한 사람이 혼자 작품을 내릴 수 있다.</b> DB 의
 * {@code UNIQUE(reporter_ref, target_type, target_id)} 가 그 보장이고, 두 번째 신고는
 * {@code 409} 다 (§13.9) — 자기가 한 일이므로 알려 준다고 새는 것도 없다.
 *
 * <p><b>누적에 대한 조치는 여기 없다</b> — 자동 정지와 검수 큐 편입은 B-57(2/2)이다.
 */
@Service
public class ReportService {

	private final ContentReportRepository reports;

	private final StoryPublisher publisher;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public ReportService(ContentReportRepository reports, StoryPublisher publisher, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.reports = reports;
		this.publisher = publisher;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 신고한다.
	 *
	 * <p><b>돌려주는 것이 없다</b> (S-11). 지금 몇 건인지도, 무엇이 일어났는지도 알리지
	 * 않는다 — 알면 임계를 역산할 수 있고, 신고자는 자기 신고가 몇 번째인지 알 이유가 없다.
	 */
	public void report(UUID reporterRef, ReportTarget target, UUID targetId, UUID sessionId,
			Integer turnNo, ReportReason reason, String detail) {
		this.transactions.executeWithoutResult(status -> {
			requireReportable(target, targetId);
			if (this.reports.findByReporterRefAndTargetTypeAndTargetId(reporterRef,
					target.columnValue(), targetId).isPresent()) {
				throw new ApiException(ErrorCode.ALREADY_EXISTS);
			}
			this.reports.save(ContentReport.of(reporterRef, target, targetId, sessionId, turnNo,
					reason, detail, Instant.now(this.clock)));
		});
	}

	/**
	 * <b>없는 작품은 신고할 수 없다</b> (§13.9 의 {@code 404}).
	 *
	 * <p>받아 두면 아무도 볼 수 없는 행이 쌓이고, 무작위 id 로 표를 채우는 길이 열린다.
	 *
	 * <p><b>턴은 여기서 확인하지 못한다.</b> 턴은 {@code play} 스토어에 있고 {@code authoring}
	 * 은 그 표를 읽지 않는다 (§5.3) — 확인하려고 경계를 넘느니 확인하지 않는 편이 낫다.
	 */
	private void requireReportable(ReportTarget target, UUID targetId) {
		if (target == ReportTarget.STORY && this.publisher.statusOf(targetId).isEmpty()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}
}
