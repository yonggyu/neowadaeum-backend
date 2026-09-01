package com.neowadaeum.authoring.blocklist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code blocklist_entry} 영속화 (§2.4). catalog 스토어에 있고 소유는 authoring 이다 (ADR-0002). */
public interface BlocklistEntryRepository extends JpaRepository<BlocklistEntryRow, UUID> {

	/** 판정에 쓰는 것만 읽는다 — {@code warn} 은 나가지 않는다 (§13-31). */
	List<BlocklistEntryRow> findBySeverity(String severity);

	/** 같은 정규화 값이 이미 있는가. 유일 제약에 걸리기 전에 사람에게 알려 준다. */
	Optional<BlocklistEntryRow> findByNormalizedValue(String normalizedValue);

	/** 관리 화면 목록. 최근 것부터 본다. */
	List<BlocklistEntryRow> findAllByOrderByUpdatedAtDesc();
}
