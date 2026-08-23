package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.Turn;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 턴 영속화 (§9.2). 조회 API(B-35 History)의 커서 페이지네이션은 그 작업에서 더한다. */
public interface TurnRepository extends JpaRepository<Turn, UUID> {

	/**
	 * 직전 턴. 다음 요청의 {@code choiceId} 를 대조할 대상이다 (I-1, §4.3-2).
	 *
	 * <p>되돌려진 턴은 제외한다 — 롤백 후에는 그 이전 턴이 마지막이다 (R14.4).
	 */
	Optional<Turn> findFirstBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(UUID sessionId);

	/**
	 * 현재 챕터에서 지난 턴 수 (R7.2).
	 *
	 * <p><b>세지 않고 컬럼에 두지 않는다.</b> {@code turn.chapter_no} 로 파생 가능한 값을 저장하면
	 * 어긋날 자리가 하나 는다 (S-9 요구사항 2).
	 */
	int countBySessionIdAndChapterNoAndDeletedAtIsNull(UUID sessionId, int chapterNo);
}
