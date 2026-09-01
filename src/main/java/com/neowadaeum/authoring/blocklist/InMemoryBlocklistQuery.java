package com.neowadaeum.authoring.blocklist;

import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.BlocklistQuery;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 슬라이스용 블록리스트 조회 구현 (S-8, ADR-0002).
 *
 * <p><b>소유 모듈은 ADR-0002 대로 authoring 이다.</b> 바뀐 것은 저장소뿐이다 —
 * {@code blocklist_entry} 테이블은 B-10, 관리 API 와 캐시 무효화는 B-49 이며 둘 다 슬라이스 밖이다.
 *
 * <p><b>항목은 비어 있다. 스텁이 아니라 빈 블록리스트다.</b> 판정기는 실제로 정규화하고 실제로
 * 대조하며, 항목이 없으면 매칭이 없는 것이 올바른 동작이다. 대조 로직 자체는 테스트 픽스처로
 * 검증한다 — <b>실제 문자열을 소스에 넣지 않는다</b> (S-11, B-31 정의).
 *
 * <p>이 빈이 존재하는 것 자체가 계약의 일부다. 없으면 부팅이 실패한다 (ADR-0002 fail-fast).
 * B-49 가 오면 이 클래스를 테이블 기반 구현으로 교체한다.
 */
@Component
public class InMemoryBlocklistQuery implements BlocklistQuery {

	private final List<BlocklistEntry> entries;

	public InMemoryBlocklistQuery() {
		this(List.of());
	}

	/** 테스트가 항목을 주입할 때 쓴다. 운영 경로는 위의 기본 생성자다. */
	public InMemoryBlocklistQuery(List<BlocklistEntry> entries) {
		this.entries = List.copyOf(entries);
	}

	@Override
	public List<BlocklistEntry> findAll() {
		return this.entries;
	}
}
