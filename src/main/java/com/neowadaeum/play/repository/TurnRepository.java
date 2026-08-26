package com.neowadaeum.play.repository;

import com.neowadaeum.play.domain.Turn;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
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
	 * 최근 턴 몇 개 — 프롬프트의 RECENT TURNS 레이어 재료다 (§5.1, R4.7, B-22).
	 *
	 * <p><b>최신이 앞이다.</b> 호출자가 뒤집어 오래된 것부터 싣는다 — 예산이 모자라면 앞에서부터
	 * 빠지는 것이 §4.4 의 축소 순서이기 때문이다.
	 *
	 * <p>되돌려진 턴은 제외한다 (R14.4). 롤백된 턴이 프롬프트에 남으면 <b>없던 일이 이야기에
	 * 계속 영향을 준다.</b>
	 */
	List<Turn> findBySessionIdAndDeletedAtIsNullOrderByTurnNoDesc(UUID sessionId, Limit limit);

	/**
	 * 현재 챕터에서 지난 턴 수 (R7.2).
	 *
	 * <p><b>세지 않고 컬럼에 두지 않는다.</b> {@code turn.chapter_no} 로 파생 가능한 값을 저장하면
	 * 어긋날 자리가 하나 는다 (S-9 요구사항 2).
	 */
	int countBySessionIdAndChapterNoAndDeletedAtIsNull(UUID sessionId, int chapterNo);
}
