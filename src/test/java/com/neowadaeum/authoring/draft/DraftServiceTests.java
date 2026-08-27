package com.neowadaeum.authoring.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-51 — <b>남의 원고는 없는 것과 구분되지 않는다</b> (I-8, §8.1).
 *
 * <p>존재 여부가 새면 원고 id 를 훑어 <b>남이 무엇을 쓰고 있는지</b> 알 수 있다. 검수 전 UGC 는
 * 작성자 말고는 볼 수 없어야 한다.
 */
class DraftServiceTests extends ContainerTestBase {

	@Autowired
	private DraftService service;

	@Autowired
	private StoryDraftRepository drafts;

	@AfterEach
	void clear() {
		this.drafts.deleteAll();
	}

	/** 새 원고는 1단계에서 시작하고 아직 검수를 거치지 않았다. */
	@Test
	void R8_1_a_new_draft_starts_at_step_one() {
		StoryDraft draft = this.service.create(UUID.randomUUID());

		assertThat(draft.getStep()).isEqualTo(1);
		assertThat(draft.getSafetyState()).isEqualTo(DraftSafetyState.CLEAN);
		assertThat(draft.getPayload()).isEqualTo("{}");
	}

	/** <b>남의 원고는 404 다</b> (I-8) — 403 이면 "있긴 있다"를 알려 준 것이다. */
	@Test
	void I8_another_authors_draft_is_not_found() {
		UUID draftId = this.service.create(UUID.randomUUID()).getId();

		assertThatThrownBy(() -> this.service.read(UUID.randomUUID(), draftId))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	/** 목록에도 내 것만 나온다. */
	@Test
	void I8_the_list_carries_only_my_drafts() {
		UUID mine = UUID.randomUUID();
		this.service.create(mine);
		this.service.create(UUID.randomUUID());

		assertThat(this.service.list(mine)).hasSize(1);
	}

	/** 남의 원고는 지워지지 않는다. 그리고 그 사실을 알려 주지도 않는다. */
	@Test
	void I8_another_authors_draft_cannot_be_deleted() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.service.create(authorRef).getId();

		this.service.delete(UUID.randomUUID(), draftId);

		assertThat(this.drafts.findById(draftId)).isPresent();
	}

	/** 단계별 입력이 그대로 남는다 — 서버는 안을 해석하지 않는다. */
	@Test
	void R8_1_a_step_payload_is_stored_as_given() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = this.service.create(authorRef).getId();

		this.service.save(authorRef, draftId, 2, "{\"worldIntro\":\"봄의 학교\"}");

		assertThat(this.service.read(authorRef, draftId).getPayload()).contains("봄의 학교");
	}

	/**
	 * <b>{@code blocked} 면 앞으로 가지 못한다</b> (R8.3).
	 *
	 * <p>서버가 거부한다 — 클라이언트 검증에만 의존하지 않는다.
	 */
	@Test
	void R8_3_a_blocked_draft_cannot_advance() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = givenBlockedDraftAtStep(authorRef, 2);

		assertThatThrownBy(() -> this.service.save(authorRef, draftId, 3, "{}"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.SAFETY_BLOCKED);
	}

	/**
	 * <b>뒤로는 갈 수 있다.</b>
	 *
	 * <p>걸린 것을 고치러 돌아가는 길까지 막으면 그 원고는 영원히 갇힌다.
	 */
	@Test
	void R8_3_a_blocked_draft_can_still_go_back() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = givenBlockedDraftAtStep(authorRef, 3);

		StoryDraft saved = this.service.save(authorRef, draftId, 2, "{\"fixed\":true}");

		assertThat(saved.getStep()).isEqualTo(2);
	}

	/** 같은 단계에 다시 저장하는 것도 막지 않는다 — 그것이 고치는 행위다. */
	@Test
	void R8_3_a_blocked_draft_can_be_edited_in_place() {
		UUID authorRef = UUID.randomUUID();
		UUID draftId = givenBlockedDraftAtStep(authorRef, 3);

		assertThat(this.service.save(authorRef, draftId, 3, "{\"fixed\":true}").getStep()).isEqualTo(3);
	}

	/** <b>개수 상한이 있다</b> (R8.12) — 없으면 한 계정이 저장소를 채운다. */
	@Test
	void R8_12_an_author_cannot_hoard_drafts() {
		UUID authorRef = UUID.randomUUID();
		for (int i = 0; i < DraftService.MAX_DRAFTS_PER_AUTHOR; i++) {
			this.service.create(authorRef);
		}

		assertThatThrownBy(() -> this.service.create(authorRef))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.ALREADY_EXISTS);
	}

	/** 지운 자리는 다시 쓸 수 있다 — 상한이 영구 봉인이 되면 안 된다. */
	@Test
	void R8_12_deleting_frees_a_slot() {
		UUID authorRef = UUID.randomUUID();
		UUID first = this.service.create(authorRef).getId();
		for (int i = 1; i < DraftService.MAX_DRAFTS_PER_AUTHOR; i++) {
			this.service.create(authorRef);
		}

		this.service.delete(authorRef, first);

		assertThat(this.service.create(authorRef)).isNotNull();
	}

	private UUID givenBlockedDraftAtStep(UUID authorRef, int step) {
		UUID draftId = this.service.create(authorRef).getId();
		this.service.save(authorRef, draftId, step, "{}");
		StoryDraft draft = this.drafts.findById(draftId).orElseThrow();
		draft.recordPrecheck(DraftSafetyState.BLOCKED, "[]", Instant.now());
		this.drafts.saveAndFlush(draft);
		return draftId;
	}
}
