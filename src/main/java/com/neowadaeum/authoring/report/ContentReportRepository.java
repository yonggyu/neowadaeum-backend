package com.neowadaeum.authoring.report;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
