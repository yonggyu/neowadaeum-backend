package com.neowadaeum.authoring.review;

/**
 * 작품의 검수 상태 (§2.3, §8.3).
 *
 * <p>값 목록은 마이그레이션의 CHECK 와 같아야 한다 — 여기 있는데 거기 없으면 저장에서 거절된다.
 */
public enum ReviewStatus {

	/** 아직 제출되지 않았다. 미리보기가 만든 작품이 이 상태다 (B-53). */
	DRAFT,

	/** 제출됐고 자동 검수를 기다린다. */
	PENDING,

	/** 자동 검수에서 걸렸다. */
	AUTO_REJECTED,

	/** 자동은 통과했고 <b>사람을 기다린다</b> (R8.6, B-55). */
	IN_REVIEW,

	/** 승인됐다. <b>곧 게시다</b> (R8.8). */
	APPROVED,

	/** 반려됐다. 수정 후 재제출한다. */
	REJECTED,

	/** 신고 누적 등으로 정지됐다 (R8.9, B-58). */
	SUSPENDED,

	/**
	 * <b>작성자가 지웠다</b> (§13-58, #290).
	 *
	 * <p><b>검수 판정이 아닌데 여기 있다.</b> {@code draft} 도 그렇다 — 이 열거형은 "누가
	 * 판정했는가"가 아니라 <b>작품이 지금 어떤 상태인가</b>를 담는 상태 머신이며(§13-9),
	 * 삭제는 그 머신의 <b>흡수 상태</b>다. {@code visibility} 에 두지 않은 이유가 이것이다:
	 * 가시성은 작성자가 {@code PATCH} 로 언제든 되돌리는 다이얼이라 <b>삭제를 되돌리는 길</b>이
	 * 되고, 넓이의 눈금(private · unlisted · public)에 눈금이 아닌 값이 섞인다.
	 *
	 * <p><b>여기서 나가는 전이는 없다.</b> 복구 경로를 만들지 않는다 — 사용자에게 '삭제'라고
	 * 말한 이상 되돌릴 수 있게 두는 쪽이 거짓말이다.
	 */
	DELETED;

	public String columnValue() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
