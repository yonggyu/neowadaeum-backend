package com.neowadaeum.play.debug;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자가 세션을 <b>찾을 때</b> 보는 한 줄 (§14 Debug, #339).
 *
 * <p><b>{@link SessionDebugView} 와 다른 계층이다.</b> 그쪽은 저장된 원문 — 게임 상태 JSON ·
 * 본문 · 프롬프트 · 응답 — 을 여는 자리이고 <b>읽는 것 자체가 감사 대상</b>이다 (R12.3, S-5).
 * 목록이 그 층에 있으면 <b>열어 본 적 없는 세션이 열람 기록에 남는다</b> — 목록을 그린 것만으로
 * 스무 줄이 생기면 감사 로그는 더 이상 <i>"이 관리자가 이 세션을 봤다"</i> 를 말하지 않는다.
 *
 * <p>그래서 이 줄이 싣는 것은 <b>식별자와 메타데이터뿐</b>이다. 원문은 하나도 없다.
 *
 * <p><b>{@code playerRef} 가 없다</b> (I-3) — {@link SessionDebugView} 와 같은 이유다. 담으면
 * 이 화면이 회원 조회 도구가 된다.
 *
 * @param storyVersionId 세션이 고정한 버전 (I-4). 작품 이름을 <b>이 값으로</b> 찾는다 —
 *                       세션이 보고 있는 것은 지금의 작품이 아니라 그때 고정된 버전이다
 * @param storyTitle 작품 이름. <b>{@code null} 일 수 있다</b> — 지워진 작품의 세션이 그렇다
 *                   (§13-58). 세션은 남고 작품이 없어진 것이므로 <b>없다고 말한다</b>
 * @param testSession 미리보기 세션인가 (I-18). 목록에서 <b>실제 플레이와 섞이면</b> 그것을
 *                    사용자 행동으로 읽게 된다
 * @param deletedAt 지워진 세션이면 그 시각. 디버그는 <b>지워졌다는 사실</b>도 본다
 */
public record SessionListView(UUID sessionId, UUID storyId, UUID storyVersionId, String storyTitle,
		String status, int turnNo, int chapterNo, boolean testSession, Instant deletedAt,
		Instant createdAt, Instant updatedAt) {
}
