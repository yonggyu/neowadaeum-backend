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
	 *
	 * <p><b>{@code payload} 를 통째로 해석하지는 않는다</b> (B-51). 보는 것은 <b>고른 조건</b>
	 * 하나뿐이며 (#326), 나머지는 아직 채우지 않아도 되는 것들이다.
	 */
	public StoryDraft save(UUID authorRef, UUID draftId, int step, String payload) {
		// #326 — 고른 조건이 원고에 없는 이름을 가리키면 여기서 막는다. 발행까지 미루면
		// 작성자는 **왜 그 엔딩이 안 나오는지**를 끝내 알지 못한다.
		DraftStoryDefinition.validateConditions(payload);
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

	/**
	 * 검수 결과를 원고에 남긴다 (B-50).
	 *
	 * <p><b>남기지 않으면 게이트가 볼 것이 없다</b> — 응답만 돌려주면 클라이언트 검증에만
	 * 의존하게 되고, 그것이 R8.3 이 막으려는 상태다.
	 */
	public void recordPrecheck(UUID authorRef, UUID draftId,
			com.neowadaeum.authoring.precheck.PrecheckScreen.Result result) {
		this.transactions.executeWithoutResult(status -> requireOwned(authorRef, draftId)
				.recordPrecheck(result.state(), findingsJson(result), Instant.now(this.clock)));
	}

	/**
	 * <b>원문도 걸린 항목도 담지 않는다</b> (R8.7, S-3, S-11).
	 *
	 * <p>{@code story_draft.safety_findings} 는 원고와 함께 보관되고 검수자가 읽는다 — 거기에
	 * 걸린 항목이 남으면 그 표가 우회 사전이 된다.
	 */
	private static String findingsJson(
			com.neowadaeum.authoring.precheck.PrecheckScreen.Result result) {
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < result.findings().size(); i++) {
			var finding = result.findings().get(i);
			if (i > 0) {
				json.append(',');
			}
			json.append("{\"field\":\"").append(finding.field())
					.append("\",\"span\":[").append(finding.span()[0]).append(',')
					.append(finding.span()[1]).append("],\"kind\":\"").append(finding.kind())
					.append("\"}");
		}
		return json.append(']').toString();
	}

	/** 원고가 만든 작품을 가리키게 한다 (B-54). */
	/**
	 * 마지막 미리보기를 원고에 붙인다 (#332).
	 *
	 * <p><b>붙이지 않으면 그 작품과 세션은 고아가 된다</b> — 작품 id 로도 원고에서도 갈 수 없고,
	 * 검수자는 <b>프롬프트만 읽고</b> 판정하게 된다.
	 */
	public void linkPreview(UUID authorRef, UUID draftId, UUID previewStoryId, UUID previewSessionId) {
		this.transactions.executeWithoutResult(status -> requireOwned(authorRef, draftId)
				.linkPreview(previewStoryId, previewSessionId, Instant.now(this.clock)));
	}

	public void linkStory(UUID authorRef, UUID draftId, UUID storyId) {
		this.transactions.executeWithoutResult(status -> requireOwned(authorRef, draftId)
				.linkStory(storyId, Instant.now(this.clock)));
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
