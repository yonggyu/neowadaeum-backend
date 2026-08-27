package com.neowadaeum.authoring.blocklist;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.support.TextNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 블록리스트 등록·삭제 (B-49, R9.4).
 *
 * <p><b>쓰기가 캐시를 버린다.</b> 등록한 항목이 다음 판정부터 걸리지 않으면, 운영자는
 * 등록했다고 믿는데 서비스는 여전히 통과시킨다 — 그 간극이 이 서비스의 실제 등급이 된다.
 *
 * <p><b>무효화는 커밋 뒤에 한다.</b> 트랜잭션 안에서 버리면 그 사이의 조회가 <b>커밋되지 않은
 * 상태를 기준으로</b> 캐시를 다시 채운다.
 *
 * <p><b>정규화는 서버가 한다</b> (R2.5). 클라이언트가 보낸 정규화 값을 믿으면, 그 값을 비워
 * 보내는 것만으로 <b>걸리지 않는 항목</b>을 등록할 수 있다.
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다.</b> 같은 클래스 안에서 부르면 프록시가 걸리지
 * 않아 애노테이션만 보고 트랜잭션이 있다고 믿게 된다 — {@code SessionStarter} 와 같은 이유로
 * {@link TransactionTemplate} 을 쓴다. 경계가 코드에 드러나고, <b>무효화가 그 밖에 있다는 것</b>도
 * 함께 드러난다.
 */
@Service
public class BlocklistAdminService {

	private final BlocklistEntryRepository entries;

	private final PersistentBlocklistQuery cache;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public BlocklistAdminService(BlocklistEntryRepository entries, PersistentBlocklistQuery cache,
			Clock clock, PlatformTransactionManager catalogTransactionManager) {
		this.entries = entries;
		this.cache = cache;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/**
	 * 항목을 등록한다.
	 *
	 * @throws ApiException {@code VALIDATION_ERROR} — 정규화하면 빈 값이 된다. 문장부호만으로
	 *     이뤄진 값이 그렇다 — 그런 항목은 <b>모든 문자열에 걸리거나 아무것에도 걸리지 않는다</b>
	 * @throws ApiException {@code ALREADY_EXISTS} — 같은 정규화 값이 이미 있다
	 */
	public UUID register(BlocklistKind kind, String value, BlocklistSeverity severity, String source) {
		String normalized = TextNormalizer.normalize(value);
		if (normalized.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}

		UUID id = this.transactions.execute(status -> {
			// DB 의 유일 제약이 마지막 방어선이고, 이 확인은 **운영자에게 왜 실패했는지**를
			// 알려주기 위한 것이다 — 제약 위반으로 500 이 나가면 그 사람은 다시 시도한다.
			this.entries.findByNormalizedValue(normalized).ifPresent(existing -> {
				throw new ApiException(ErrorCode.ALREADY_EXISTS);
			});
			return this.entries.save(BlocklistEntryRow.of(kind, value, severity, source,
					Instant.now(this.clock))).getId();
		});

		this.cache.invalidate();
		return id;
	}

	/** 항목을 지운다. <b>없어도 성공이다</b> — 삭제는 상태를 맞추는 요청이다. */
	public void remove(UUID id) {
		this.transactions.executeWithoutResult(
				status -> this.entries.findById(id).ifPresent(this.entries::delete));
		this.cache.invalidate();
	}

	/** 관리 화면 목록. 최근 것부터. */
	public List<BlocklistEntryRow> list() {
		return this.transactions.execute(status -> this.entries.findAllByOrderByUpdatedAtDesc());
	}
}
