package com.neowadaeum.play.stats;

import java.util.UUID;

/**
 * 도달 집계의 키 — 작품 하나의 엔딩 하나 (§2.6, B-39).
 *
 * <p><b>{@code endingId} 로 센다.</b> 그것이 그 작품의 몇 번째 엔딩인지는 {@code catalog} 의
 * 지식이고 (§5.3), 번호로 합치는 것은 저쪽의 일이다 — {@code play} 가 그 변환을 하려면 스키마를
 * 가로질러야 한다.
 *
 * <p>살아 있는 세션({@link CompletedSessionTally})과 파기된 세션이 남긴 몫
 * ({@link PurgedSessionTally})이 <b>같은 키로 합쳐진다.</b> 두 자리가 서로 다른 키를 쓰면
 * 합계가 조용히 갈라진다.
 */
record ReachedEnding(UUID storyId, UUID endingId) {
}
