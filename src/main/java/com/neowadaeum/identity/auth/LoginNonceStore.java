package com.neowadaeum.identity.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 nonce 의 발급과 <b>1회성 소비</b> (§13-87).
 *
 * <p><b>서버가 만들어야 대조가 성립한다.</b> 클라이언트가 만든 값을 로그인 요청에 함께 받으면
 * <b>같은 요청 안의 값을 자기 자신과 비교</b>하는 꼴이라 아무것도 막지 못한다. 그래서 순서가
 * 이렇다 — 여기서 발급 → 클라이언트가 GIS 에 싣는다 → 그 값이 ID 토큰의 {@code nonce}
 * 클레임으로 돌아온다 → {@link OAuthLoginService} 가 여기서 소비하며 대조한다.
 *
 * <p><b>난수를 쓰는 것은 I-15 위반이 아니다.</b> I-15 가 금지하는 것은 <b>판정·분기·엔딩·상태
 * 변화량·재화</b>에 난수를 들이는 것이고, 그 보강 문장이 <i>"요청 ID·UUID"</i> 를 명시적으로
 * 허용한다. nonce 는 게임 상태를 하나도 건드리지 않는 <b>요청 식별자</b>이며, 여기서 예측
 * 가능한 값을 쓰면 그것이야말로 이 기능을 무의미하게 만든다 — 그래서 {@link SecureRandom} 이다.
 *
 * <p><b>소비는 원자적이어야 한다.</b> 조회와 삭제가 갈라지면 같은 nonce 를 든 두 요청이 <b>둘 다
 * 조회에 성공한 뒤 둘 다 통과</b>한다 — 막으려던 재생이 정확히 그 창으로 들어온다. Redis 의
 * {@code DEL} 은 지운 개수를 돌려주므로 <b>승자가 하나뿐</b>이며, {@code common/web} 의
 * {@code IdempotencyStore} 가 {@code setIfAbsent} 로 쓰는 것과 같은 성질이다.
 *
 * <p><b>Redis 인 이유는 프로세스 간 공유다.</b> 인스턴스가 둘이면 인메모리 맵은 발급한 쪽이
 * 아닌 인스턴스에서 <b>항상 실패</b>한다.
 */
@Component
public class LoginNonceStore {

	/**
	 * 값의 길이. 256비트다.
	 *
	 * <p>추측할 수 없어야 하는 값이고, ID 토큰의 클레임 하나에 실리므로 길이가 비용이 되지 않는다.
	 */
	private static final int BYTES = 32;

	/**
	 * 수명 — <b>짧다.</b>
	 *
	 * <p>이 창이 곧 재생이 가능한 창이다. 로그인 왕복(발급 → GIS → 로그인)은 사람이 계정을 고르는
	 * 시간을 포함해도 이보다 짧고, 넘겼다면 <b>다시 받는 것이 옳다</b> — 만료된 nonce 로 통과시켜
	 * 주는 것보다 한 번 더 부르는 편이 싸다.
	 *
	 * <p>설정 키로 두지 않는다. 조정할 근거가 생기기 전의 설정 키는 <b>부팅에서 값을 요구하는
	 * 자리만 하나 늘린다</b> (§7.3 — 기본값 패턴을 쓰지 않으므로).
	 */
	static final Duration TTL = Duration.ofMinutes(5);

	/** 키 접두어. <b>여기에 회원을 가리키는 것이 하나도 없다</b> — 로그인 전이다 (I-3). */
	private static final String KEY_PREFIX = "auth:nonce:";

	/**
	 * 값은 읽지 않는다 — <b>존재 자체가 정보</b>다.
	 *
	 * <p>무언가를 담으면 그 자리에 회원 정보가 붙는 날이 온다. 담을 것이 없다는 사실을 자리로
	 * 못박는다.
	 */
	private static final String PRESENT = "1";

	private final SecureRandom random = new SecureRandom();

	private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

	private final StringRedisTemplate redis;

	public LoginNonceStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * 새 nonce 를 만들어 보관한다.
	 *
	 * <p>보관에 {@code setIfAbsent} 를 쓰지 않는다 — 256비트가 충돌하는 일은 없고, <b>1회성을
	 * 보장하는 것은 발급이 아니라 {@link #consume} 의 원자적 삭제</b>다. 여기서 조건부 쓰기를
	 * 하면 지키는 것 없이 분기만 하나 는다.
	 */
	public LoginNonce issue() {
		byte[] bytes = new byte[BYTES];
		this.random.nextBytes(bytes);
		String value = this.encoder.encodeToString(bytes);

		this.redis.opsForValue().set(keyOf(value), PRESENT, TTL);
		return new LoginNonce(value, TTL.toSeconds());
	}

	/**
	 * 한 번만 통과한다.
	 *
	 * <p><b>없든 · 만료됐든 · 이미 쓰였든 · 애초에 오지 않았든 전부 {@code false} 다.</b> 어느
	 * 쪽인지 돌려주면 부르는 쪽이 그것을 응답으로 옮기게 되고, 그 순간 공격자에게 서버의 상태를
	 * 알려 주는 창구가 된다 (S-6).
	 *
	 * @param nonce ID 토큰이 실어 온 {@code nonce} 클레임. 없으면 {@code null}
	 * @return 이 호출이 그 값을 소비했으면 {@code true}
	 */
	public boolean consume(String nonce) {
		if (nonce == null || nonce.isBlank()) {
			// 저장소를 부르지 않는다. 빈 값으로 만든 키를 조회하게 두면 그 키가 실재하는 날
			// 아무 토큰이나 통과한다.
			return false;
		}
		return Boolean.TRUE.equals(this.redis.delete(keyOf(nonce)));
	}

	private static String keyOf(String nonce) {
		return KEY_PREFIX + nonce;
	}
}
