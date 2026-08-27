package com.neowadaeum.common.spi;

import java.util.UUID;

/**
 * 미리보기 세션을 연다 (R8.13, B-53).
 *
 * <p><b>{@code authoring} 은 {@code play} 를 알지 못한다.</b> 그 경계를 지키면서 세션을 여는
 * 길이 이 인터페이스다 — {@link SafetyClassifier} · {@link OutlineDrafter} 와 같은 모양이다.
 *
 * <p><b>{@code is_test_session = true} 로 연다</b> (I-18). 그 세션에서만 자유입력이 허용되며
 * (R14.3), 미리보기는 완주 통계에도 들어가지 않는다.
 */
public interface TestSessionStarter {

	/**
	 * 미리보기 세션을 시작하고 첫 턴을 만든다.
	 *
	 * @param turnLimit 이 세션이 만들 수 있는 턴 수 (R8.13). <b>서버가 막는다</b>
	 * @return 세션 참조
	 */
	TestSession start(UUID playerRef, UUID storyId, UUID storyVersionId, int turnLimit);

	/**
	 * 열린 미리보기 세션.
	 *
	 * @param turnNo 만들어진 첫 턴의 번호
	 */
	record TestSession(UUID sessionId, int turnNo) {
	}
}
