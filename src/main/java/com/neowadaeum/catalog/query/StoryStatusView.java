package com.neowadaeum.catalog.query;

import java.util.UUID;

/**
 * 이어하기 판정에 쓰는 작품 쪽 사실 (§13.4, §4.7).
 *
 * <p><b>세션이 고정한 버전과 비교할 값</b>이 여기 있다 (R2.1) — 다르면 {@code version_changed}
 * 다. 정지 여부도 함께 온다 (R8.10, R13.3).
 *
 * @param currentVersionId 지금 시작하면 붙는 버전. 세션의 것과 다를 수 있다
 * @param reviewStatus     {@code suspended} 면 읽기 전용이다
 */
public record StoryStatusView(UUID currentVersionId, String reviewStatus) {

	/** R8.10 — UGC 정지. 새 턴을 만들 수 없고 기존 기록 열람만 된다. */
	public boolean suspended() {
		return "suspended".equals(this.reviewStatus);
	}
}
