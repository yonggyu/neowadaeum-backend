package com.neowadaeum.authoring.draft;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원고 읽고 쓰기 (§8.1, §13.8).
 *
 * <p><b>남의 원고는 없는 것과 구분되지 않는다</b> (I-8, I-3). 존재 여부가 새면 원고 id 를 훑어
 * <b>남이 무엇을 쓰고 있는지</b> 알 수 있다 — 검수 전 UGC 는 작성자 말고는 볼 수 없어야 한다.
 *
 * <p><b>개수 상한을 둔다</b> (R8.12). 없으면 한 계정이 저장소를 채운다.
 *
 * <p>{@code @Transactional} 대신 {@link TransactionTemplate} 을 쓴다 — 같은 클래스 안에서 부르면
 * 프록시가 걸리지 않는다.
 */
@Service
public class DraftService {

	/**
	 * 작성자당 원고 상한 (R8.12).
	 *
	 * <p>원문은 값을 정하지 않는다 (§13-32). 작성 중인 원고가 열 개를 넘는 상태는 <b>쓰고 있는
	 * 것이 아니라 쌓아 둔 것</b>이며, 상한이 낮으면 지우고 다시 만들면 된다.
	 */
	static final int MAX_DRAFTS_PER_AUTHOR = 10;

	private final StoryDraftRepository drafts;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public DraftService(StoryDraftRepository drafts, Clock clock,
			PlatformTransactionManager catalogTransactionManager) {
		this.drafts = drafts;
		this.clock = clock;
		this.transactions = new TransactionTemplate(catalogTransactionManager);
	}

	/** @throws ApiException {@code ALREADY_EXISTS} — 상한에 닿았다 (R8.12) */
	public StoryDraft create(UUID authorRef) {
		return this.transactions.execute(status -> {
			if (this.drafts.countByAuthorRef(authorRef) >= MAX_DRAFTS_PER_AUTHOR) {
				throw new ApiException(ErrorCode.ALREADY_EXISTS);
			}
			return this.drafts.save(StoryDraft.start(authorRef, Instant.now(this.clock)));
		});
	}

	/** @throws ApiException {@code NOT_FOUND} — 없거나 <b>남의</b> 원고 (I-8) */
	public StoryDraft read(UUID authorRef, UUID draftId) {
		return this.transactions.execute(status -> requireOwned(authorRef, draftId));
	}

	/** 내 원고만 나온다. */
	public List<StoryDraft> list(UUID authorRef) {
		return this.transactions.execute(status -> this.drafts.findByAuthorRefOrderByUpdatedAtDesc(authorRef));
	}

	/**
	 * 단계별 입력을 저장한다.
	 *
	 * <p><b>{@code blocked} 면 앞으로 가지 못한다</b> (R8.3) — 서버가 거부한다. 다만 <b>뒤로는
	 * 갈 수 있다</b>: 걸린 것을 고치러 돌아가는 길까지 막으면 그 원고는 영원히 갇힌다.
	 */
	public StoryDraft save(UUID authorRef, UUID draftId, int step, String payload) {
		return this.transactions.execute(status -> {
			StoryDraft draft = requireOwned(authorRef, draftId);
			if (draft.getSafetyState() == DraftSafetyState.BLOCKED && step > draft.getStep()) {
				throw new ApiException(ErrorCode.SAFETY_BLOCKED);
			}
			draft.save(step, payload, Instant.now(this.clock));
			return draft;
		});
	}

	/** 지운다. <b>없어도 성공이다</b> — 삭제는 상태를 맞추는 요청이다. 남의 것은 지우지 못한다. */
	public void delete(UUID authorRef, UUID draftId) {
		this.transactions.executeWithoutResult(status -> this.drafts.findById(draftId)
				.filter(draft -> draft.isOwnedBy(authorRef))
				.ifPresent(this.drafts::delete));
	}

	private StoryDraft requireOwned(UUID authorRef, UUID draftId) {
		StoryDraft draft = this.drafts.findById(draftId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!draft.isOwnedBy(authorRef)) {
			// 남의 원고와 없는 원고를 구분하지 않는다 (I-8).
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return draft;
	}
}
