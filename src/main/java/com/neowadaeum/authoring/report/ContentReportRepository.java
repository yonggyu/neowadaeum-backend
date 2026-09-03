package com.neowadaeum.authoring.report;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 신고 (§2.4). catalog 스토어에 있고 소유는 authoring 이다 (ADR-0002). */
public interface ContentReportRepository extends JpaRepository<ContentReport, UUID> {

	/**
	 * 이 사람이 이 대상을 이미 신고했는가.
	 *
	 * <p>DB 의 유일 제약과 <b>같은 것을 두 번 본다.</b> 제약이 진실이고 이것은 <b>중복을
	 * 성공으로 답하기 위한</b> 조회다 — 예외를 잡아 성공으로 바꾸면 어떤 제약이 걸린 것인지
	 * 구분하지 못한다.
	 */
	Optional<ContentReport> findByReporterRefAndTargetTypeAndTargetId(UUID reporterRef,
			String targetType, UUID targetId);

	/**
	 * 이 대상에 <b>몇 사람이</b> 신고했는가 (R8.9).
	 *
	 * <p>유일 제약 덕분에 행 수가 곧 사람 수다 — 중복이 세어지면 한 사람이 혼자 작품을 내릴
	 * 수 있다.
	 */
	long countByTargetTypeAndTargetId(String targetType, UUID targetId);

	/**
	 * 이 대상의 신고를 최근 것부터 (§13-62).
	 *
	 * <p><b>한 번에 다 읽지 않는다.</b> 임계에 닿아 큐에 오른 작품에도 신고는 계속 쌓이며,
	 * 판정에 먼저 필요한 것은 <b>무엇이 몇 건인지</b>다 — 그 답은 아래 집계가 <b>전건</b>으로
	 * 준다.
	 */
	List<ContentReport> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType,
			UUID targetId, Limit limit);

	/**
	 * 사유별로 <b>몇 건인가</b> (§13-62).
	 *
	 * <p>목록과 달리 <b>상한이 없다.</b> 검수자가 먼저 보는 것이 이 숫자이므로, 그것이 페이지에
	 * 잘리면 <b>같은 작품이 볼 때마다 다르게 보인다.</b>
	 */
	@Query("""
			SELECT r.reason AS reason, COUNT(r) AS reportCount
			FROM ContentReport r
			WHERE r.targetType = :targetType AND r.targetId = :targetId
			GROUP BY r.reason
			""")
	List<ReasonTally> tallyByReason(@Param("targetType") String targetType,
			@Param("targetId") UUID targetId);

	/**
	 * 사유 하나의 집계.
	 *
	 * <p>{@code reason} 은 <b>컬럼 표기 그대로</b>다 — 열거형으로 바꾸는 것은 서비스가 한다.
	 */
	interface ReasonTally {

		String getReason();

		long getReportCount();
	}
}
