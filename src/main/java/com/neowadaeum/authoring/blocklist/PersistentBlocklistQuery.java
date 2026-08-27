package com.neowadaeum.authoring.blocklist;

import com.neowadaeum.common.spi.BlocklistEntry;
import com.neowadaeum.common.spi.BlocklistQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블록리스트를 실제로 읽는다 (B-49, R2.5, R9.4, ADR-0002).
 *
 * <p><b>판정은 매 턴 일어난다.</b> 그래서 매번 DB 를 때리지 않고 <b>스냅샷을 들고 있는다</b> —
 * 호출 비용이 그대로 턴 지연이 되기 때문이다.
 *
 * <p><b>캐시가 갱신을 삼키면 안 된다.</b> 등록한 항목이 다음 판정부터 걸리지 않으면, 운영자는
 * 등록했다고 믿는데 서비스는 여전히 통과시킨다 — 그것이 가장 나쁜 상태다. 그래서 쓰기 쪽이
 * {@link #invalidate()} 를 부르고, 그것과 별개로 스냅샷에 <b>수명</b>을 둔다.
 *
 * <p><b>수명을 두는 이유는 인스턴스가 여럿일 수 있기 때문이다</b> (§13-31). 등록을 받은 인스턴스는
 * 즉시 갱신되지만 나머지는 모른다 — 수명이 그 간극의 상한이다. 공유 신호로 바꾸는 것은 배포
 * 형태가 정해질 때다 (B-63).
 *
 * <p><b>fail-closed 를 뒤집지 않는다</b> (ADR-0002). 조회에 실패하면 예외를 그대로 올린다 —
 * 판정기가 그것을 차단으로 다룬다. <b>실패했을 때 옛 스냅샷을 계속 쓰지 않는다</b>: 블록리스트를
 * 못 읽는 상태에서 오래된 목록으로 통과시키는 것은 "읽을 수 없다"를 "괜찮다"로 바꾸는 일이다.
 */
@Component
@Primary
public class PersistentBlocklistQuery implements BlocklistQuery {

	/**
	 * 스냅샷 수명.
	 *
	 * <p>짧을수록 인스턴스 간 간극이 줄고, 짧을수록 DB 를 자주 때린다. 1분은 <b>운영자가
	 * 등록하고 확인하기까지</b>의 시간보다 짧다.
	 */
	static final Duration SNAPSHOT_TTL = Duration.ofMinutes(1);

	private final BlocklistEntryRepository entries;

	private final Clock clock;

	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

	public PersistentBlocklistQuery(BlocklistEntryRepository entries, Clock clock) {
		this.entries = entries;
		this.clock = clock;
	}

	@Override
	public List<BlocklistEntry> findAll() {
		Snapshot current = this.snapshot.get();
		Instant now = this.clock.instant();
		if (current != null && current.isFreshAt(now)) {
			return current.entries();
		}
		Snapshot loaded = new Snapshot(load(), now);
		this.snapshot.set(loaded);
		return loaded.entries();
	}

	/**
	 * 다음 조회가 DB 를 다시 읽게 한다.
	 *
	 * <p><b>지우기만 하고 미리 채우지 않는다.</b> 채우려면 쓰기 트랜잭션 안에서 읽어야 하고,
	 * 그러면 커밋 전의 목록이 캐시에 앉는다.
	 */
	public void invalidate() {
		this.snapshot.set(null);
	}

	/** <b>{@code block} 만 나간다</b> (§13-31) — 경고 항목을 내보내면 경고가 차단이 된다. */
	@Transactional(value = "catalogTransactionManager", readOnly = true)
	protected List<BlocklistEntry> load() {
		return this.entries.findBySeverity(BlocklistSeverity.BLOCK.columnValue()).stream()
				.map(row -> new BlocklistEntry(row.getNormalizedValue(), row.getKind().category()))
				.toList();
	}

	private record Snapshot(List<BlocklistEntry> entries, Instant loadedAt) {

		boolean isFreshAt(Instant now) {
			return now.isBefore(this.loadedAt.plus(SNAPSHOT_TTL));
		}
	}
}
