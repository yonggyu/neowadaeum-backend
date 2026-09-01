package com.neowadaeum.play.orchestrator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 서버가 선택지 식별자를 발급한다 (I-1, §13-9).
 *
 * <p><b>I-1 — 클라이언트가 보낸 {@code text} 는 어떤 경우에도 신뢰하지 않는다.</b> 다음 요청은
 * {@code choiceId} 만 보내고, 서버는 그것이 <b>직전 턴이 발급한 것인지</b>로 검증한다. 텍스트를
 * 신뢰하면 사용자가 존재하지 않는 선택지를 만들어 보낼 수 있다.
 *
 * <p>형식은 §13-9 의 {@code {turnNo}-{order}-{shortHash}} 다. 앞의 두 조각이 이미 세션 안에서
 * 유일하므로 <b>이전 턴의 {@code choiceId} 는 재사용될 수 없다</b> — 턴 번호가 다르기 때문이다.
 * 뒤의 해시는 같은 좌표에 다른 선택지 텍스트가 오면 값이 달라지게 해, 재생성된 턴의 식별자가
 * 우연히 겹치는 것을 막는다.
 *
 * <p><b>난수를 쓰지 않는다</b> (I-15). 같은 입력은 같은 식별자를 낸다 — 결정론 E2E(B-44)가
 * 식별자까지 재현할 수 있어야 한다. 추측 불가능성은 여기서 요구되는 성질이 아니다. 식별자는
 * 직전 턴이 발급한 것과 대조되므로, 남의 것을 알아도 자기 세션에서는 쓸 수 없다.
 */
public final class ChoiceIdIssuer {

	/** 좌표가 이미 유일하므로 해시는 충돌 회피가 아니라 구분용이다. 짧게 쓴다. */
	private static final int SHORT_HASH_BYTES = 4;

	private ChoiceIdIssuer() {
	}

	/**
	 * @param sessionId 세션. 다른 세션과 값이 겹치지 않게 한다
	 * @param turnNo    이 선택지가 실린 턴 번호
	 * @param order     표시 순서 (1부터)
	 * @param text      선택지 텍스트. 값이 바뀌면 식별자도 바뀐다
	 */
	public static String issue(UUID sessionId, int turnNo, int order, String text) {
		if (sessionId == null) {
			throw new IllegalArgumentException("sessionId is required");
		}
		if (turnNo < 1 || order < 1) {
			throw new IllegalArgumentException("turnNo and order start at 1");
		}
		return "%d-%d-%s".formatted(turnNo, order, shortHash(sessionId, turnNo, order, text));
	}

	private static String shortHash(UUID sessionId, int turnNo, int order, String text) {
		String seed = sessionId + "|" + turnNo + "|" + order + "|" + ((text != null) ? text : "");
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
			byte[] head = new byte[SHORT_HASH_BYTES];
			System.arraycopy(digest, 0, head, 0, SHORT_HASH_BYTES);
			return HexFormat.of().formatHex(head);
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 은 JDK 필수 알고리즘이다. 여기에 오면 런타임이 깨진 것이다.
			throw new IllegalStateException("SHA-256 is required by the JDK specification", ex);
		}
	}
}
