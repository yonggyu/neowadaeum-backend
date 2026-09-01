package com.neowadaeum.common.spi;

import java.util.UUID;

/**
 * 원문 열람 기록 (R12.3, S-5).
 *
 * <p><b>{@link AdminAuditRecorder} 와 다른 점이 하나 있다 — 이쪽은 실패하면 읽지 못한다.</b>
 * 행위 감사는 남기지 못해도 그 행위를 막지 않지만, 열람 감사는 <b>남기지 못하는 열람을 허용하면
 * 기록되지 않는 열람 경로가 생긴다.</b> 그것이 S-5 가 막으려는 바로 그 상태다.
 *
 * <p>구현은 데이터를 가진 모듈이 한다 (ADR-0002 와 같은 형태). 부르는 쪽은 어느 스토어에
 * 남는지 모른다.
 */
public interface AccessAuditRecorder {

	/**
	 * 열람을 남긴다.
	 *
	 * @param adminUserId 읽은 사람. <b>{@code playerRef} 가 아니다</b> — 감사는 사람을 가리켜야 한다
	 * @param resource 읽은 자원의 종류
	 * @param resourceId 읽은 것 하나. 여러 건을 읽으면 <b>건마다</b> 남는다 — 묶어서 한 줄로 남기면
	 *     무엇을 봤는지가 사라진다
	 */
	void record(UUID adminUserId, AuditedResource resource, UUID resourceId);
}
