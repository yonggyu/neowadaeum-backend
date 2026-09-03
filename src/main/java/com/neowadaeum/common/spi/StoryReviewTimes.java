package com.neowadaeum.common.spi;

import java.time.Instant;

/**
 * 한 작품의 <b>신청 시각과 승인 시각</b> (§13-57, #290).
 *
 * <p><b>둘 다 검수 이력에서 파생한다.</b> {@code story} 에 컬럼을 더하지 않는 이유는 §13-48 ·
 * §13-50 과 같다 — 같은 사실을 두 곳에 두면 어긋났을 때 어느 쪽이 진실인지 매번 문제가 된다.
 *
 * <p><b>{@code null} 은 "그런 일이 아직 없다"이다.</b> 지어낸 시각을 넣지 않는다 — 화면이
 * <b>"2월 21일 신청"</b> 이라고 적는 순간 그것은 사실 진술이 된다.
 *
 * @param submittedAt 이 작품이 <b>지금 회차의 검수를 요청한</b> 시각. 검수를 요청한 적이 없으면
 * {@code null} — 미리보기로만 만들어진 작품({@code draft})이 그렇다
 * @param reviewedAt 그 회차에서 <b>사람이 마지막으로 판정한</b> 시각. 아직 아무도 보지 않았거나
 * 자동 검수만으로 열린 작품이면 {@code null} — {@code unlisted} 는 사람을 거치지 않는다 (R8.6)
 */
public record StoryReviewTimes(Instant submittedAt, Instant reviewedAt) {

	/** 검수 이력이 하나도 없다. 두 값 모두 {@code null} 이다. */
	public static final StoryReviewTimes NONE = new StoryReviewTimes(null, null);

}
