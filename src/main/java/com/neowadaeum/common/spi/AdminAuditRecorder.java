package com.neowadaeum.common.spi;

import java.util.Map;
import java.util.UUID;

/**
 * 관리자 행위 감사 (R14.5, S-4).
 *
 * <p>§14 는 <b>모든 관리자 액션</b>을 남기라고 한다. <b>거절된 시도도 포함이다</b> —
 * 허용목록 밖에서 온 요청이야말로 남아야 할 기록이다.
 *
 * <p><b>구현은 {@code promptlog} 를 소유한 모듈이다</b> ({@code ai.log}). 표가 그 스키마에 있고
 * (§2.7), 감사 기록을 부르는 쪽({@code admin} · {@code identity})은 그 스토어를 모른다 —
 * 블록리스트와 같은 형태다 (ADR-0002).
 *
 * <p><b>기록 실패가 호출자를 막지 않는다.</b> 다만 조용히 넘어가지도 않는다 — 감사 기록이
 * 남지 않는 관리자 행위는 그 자체가 사고이며 로그에 남는다.
 */
public interface AdminAuditRecorder {

	/**
	 * @param adminUserId 행위자. {@code identity} 의 {@code user.id} 다 — 관리자 감사는 <b>사람</b>을
	 *     가리켜야 하므로 {@code playerRef} 로 익명화하지 않는다 (R14.5 와 I-3 이 만나는 유일한 지점)
	 * @param action      {@code admin.debug.read} 처럼 점으로 구분한 이름
	 * @param targetType  대상 종류. {@code session} · {@code story} 등
	 * @param targetId    대상 식별자. 없을 수 있다
	 * @param payload     맥락. <b>원문·토큰·이메일을 담지 않는다</b> (S-3)
	 * @param ipHash      접속자 해시. <b>원문 IP 를 넘기지 않는다</b> (§12)
	 */
	void record(UUID adminUserId, String action, String targetType, UUID targetId,
			Map<String, Object> payload, String ipHash);
}
