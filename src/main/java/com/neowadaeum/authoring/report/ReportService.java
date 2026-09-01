package com.neowadaeum.authoring.report;

import com.neowadaeum.catalog.publish.StoryPublisher;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 신고와 자동 정지 (§8.4, R8.9, 세이프티 L3).
 *
 * <p>L0~L2 는 <b>만들어지는 시점</b>에 본다. 이미 게시된 작품에 대해서는 사람이 알려 주는
 * 길뿐이며, 그것이 이 자리다 (R13.5 — Play 화면 Menu 의 "신고"가 유일한 사용자 경로다).
 *
 * <p><b>같은 사람이 같은 대상을 두 번 신고해도 한 건이다.</b> 누적이 자동 정지의 근거이므로
 * 중복이 세어지면 <b>한 사람이 혼자 작품을 내릴 수 있다.</b>
 *
 * <p><b>두 번째 신고는 {@code 409} 다</b> (§13.9). 계약이 그렇게 적었고, 사용자에게는
 * "이미 신고했다"가 <b>접수됐다</b>보다 정확한 사실이다 — 자기가 한 일이므로 알려 준다고
 * 새는 것도 없다.
 *
 * <p><b>자동으로 내린 것을 자동으로 올리지 않는다.</b> 정지된 작품은 검수 큐에서 사람의
 * 판정을 기다린다 (B-55) — 그러지 않으면 신고가 곧 판정이 된다.
 */
@Service
public class ReportService {

	private final ContentReportRepository reports;

	private final StoryPublisher publisher;

	private final SuspensionThresholds thresholds;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public ReportService(ContentReportRepository reports, StoryPublisher publisher,
			SuspensionThresholds thresholds, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.reports = reports;
		this.publisher = publisher;
		this.thresholds = thresholds;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 신고한다.
	 *
	 * <p><b>임계 판정은 결정론적이다</b> (I-15) — 세어서 비교하는 것이 전부다. 표본이나 확률이
	 * 끼어들면 같은 신고 수에 어떤 작품은 내려가고 어떤 작품은 남는다.
	 *
	 * @return 접수 결과. <b>임계값도 지금 몇 건인지도 담지 않는다</b> (S-11) — 알면 그 아래로
	 *     관리할 수 있고, 신고자는 자기 신고가 몇 번째인지 알 이유가 없다
	 */
	public Receipt report(UUID reporterRef, ReportTarget target, UUID targetId, UUID sessionId,
			Integer turnNo, ReportReason reason, String detail) {
		return this.transactions.execute(status -> {
			requireReportable(target, targetId);
			if (this.reports.findByReporterRefAndTargetTypeAndTargetId(reporterRef,
					target.columnValue(), targetId).isPresent()) {
				throw new ApiException(ErrorCode.ALREADY_EXISTS);
			}
			this.reports.save(ContentReport.of(reporterRef, target, targetId, sessionId, turnNo,
					reason, detail, Instant.now(this.clock)));
			return new Receipt(suspendIfAccumulated(target, targetId));
		});
	}

	/**
	 * <b>없는 작품은 신고할 수 없다</b> (§13.9 의 {@code 404}).
	 *
	 * <p>받아 두면 아무도 볼 수 없는 행이 쌓이고, 무작위 id 로 표를 채우는 길이 열린다.
	 *
	 * <p><b>턴은 여기서 확인하지 못한다.</b> 턴은 {@code play} 스토어에 있고 {@code authoring}
	 * 은 그 표를 읽지 않는다 (§5.3) — 확인하려고 경계를 넘느니 확인하지 않는 편이 낫다.
	 * 대신 턴 신고는 정지 임계에 세지 않으므로 (§13-41) 남용의 값어치가 없다.
	 */
	private void requireReportable(ReportTarget target, UUID targetId) {
		if (target == ReportTarget.STORY && this.publisher.statusOf(targetId).isEmpty()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	/**
	 * 임계에 닿았으면 내린다 (R8.9).
	 *
	 * <p><b>작품 신고만 센다</b> (§13-41). 턴 신고에서 작품을 알아내려면 {@code authoring} 이
	 * {@code play} 의 세션 표를 읽어야 하고 그것은 모듈 경계를 깬다 — 그리고 세 사람이 서로
	 * 다른 턴을 신고한 것과 세 사람이 <b>그 작품</b>을 신고한 것은 다른 사실이다.
	 *
	 * <p><b>임계가 설정되지 않았으면 내리지 않는다.</b> 임의의 기본값을 코드에 두면 그 값이 곧
	 * 정책이 되고, 아무도 그것을 정한 적이 없다.
	 */
	private boolean suspendIfAccumulated(ReportTarget target, UUID targetId) {
		if (target != ReportTarget.STORY) {
			return false;
		}
		return this.publisher.statusOf(targetId).map(stored -> {
			OptionalInt threshold = this.thresholds.forVisibility(stored.visibility());
			if (threshold.isEmpty()) {
				return false;
			}
			long reporters = this.reports.countByTargetTypeAndTargetId(target.columnValue(), targetId);
			return reporters >= threshold.getAsInt() && this.publisher.suspend(targetId);
		}).orElse(false);
	}

	/**
	 * 접수 결과.
	 *
	 * @param suspended 이 신고가 작품을 내렸는가. <b>신고자에게 나가지 않는다</b> — 내려갔다는
	 *     사실을 알려 주면 임계를 역산할 수 있다 (S-11). 감사와 테스트가 쓴다
	 */
	public record Receipt(boolean suspended) {
	}
}
