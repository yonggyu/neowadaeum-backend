package com.neowadaeum.identity.access;

import com.neowadaeum.identity.auth.AuthTokenService;
import com.neowadaeum.identity.auth.JwtProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AdminAuditRecorder;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.domain.UserRole;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * B-40 — <b>세 조건은 AND 다</b> (S-4, R14.6).
 *
 * <p>역할이 맞아도 허용목록 밖이면 막히고, 그 반대도 같다. 하나가 통과했다고 나머지를
 * 건너뛰지 않는다 — 그것이 S-4 가 <b>"모두 요구한다"</b> 고 적은 이유다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AdminAccessGuardTests {

	/** 문서용 예약 대역이다 (RFC 5737). 운영 주소를 테스트에 적지 않는다 (S-11). */
	private static final String ALLOWED_CIDR = "203.0.113.0/24";

	private static final String ALLOWED_IP = "203.0.113.10";

	private static final String OTHER_IP = "198.51.100.7";

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	private final UserRepository users = mock(UserRepository.class);

	private final AdminAuditRecorder audit = mock(AdminAuditRecorder.class);

	private final UUID playerRef = UUID.randomUUID();

	/**
	 * 승격 토큰은 <b>실제로 발급해서</b> 쓴다.
	 *
	 * <p>가짜로 통과시키면 <b>용도 구분이 실제로 있는지</b>를 확인하지 못한다 — 액세스 토큰이
	 * 관리자 문을 열지 않는다는 것이 이 층의 핵심이다.
	 */
	private final AuthTokenService tokens = new AuthTokenService(
			new JwtProperties("test-only-secret-that-is-long-enough-32", java.time.Duration.ofMinutes(30),
					java.time.Duration.ofDays(30), java.time.Duration.ofMinutes(15)),
			java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

	private AdminAccessGuard guardWith(List<String> allowedIps) {
		return new AdminAccessGuard(this.users, new AdminAccessProperties(allowedIps), this.audit,
				this.tokens);
	}

	/** 역할과 허용목록이 맞으면 그 층은 통과하고, 행위자의 {@code user.id} 가 나온다 (R14.5). */
	@Test
	void SEC4_an_admin_from_an_allowed_ip_passes() {
		UUID userId = givenUser(UserRole.ADMIN);

		assertThat(guardWith(List.of(ALLOWED_CIDR)).requireRoleAndNetwork(this.playerRef, requestFrom(ALLOWED_IP)))
				.isEqualTo(userId);
		verify(this.audit, never()).record(any(), any(), any(), any(), any(), any());
	}

	/** <b>역할이 없으면 허용목록 안이라도 막힌다.</b> */
	@Test
	void SEC4_a_normal_user_is_denied_even_from_an_allowed_ip() {
		givenUser(UserRole.USER);

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_CIDR)).requireRoleAndNetwork(this.playerRef,
				requestFrom(ALLOWED_IP)))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	/** <b>허용목록 밖이면 관리자라도 막힌다.</b> */
	@Test
	void SEC4_an_admin_from_another_ip_is_denied() {
		givenUser(UserRole.ADMIN);

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_CIDR)).requireRoleAndNetwork(this.playerRef,
				requestFrom(OTHER_IP)))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * <b>허용목록이 비어 있으면 아무도 통과하지 못한다.</b>
	 *
	 * <p>비어 있음을 "제한 없음"으로 읽으면 <b>설정 누락이 전면 허용이 된다.</b>
	 */
	@Test
	void SEC4_an_empty_allowlist_denies_everyone() {
		givenUser(UserRole.ADMIN);

		assertThatThrownBy(() -> guardWith(List.of()).requireRoleAndNetwork(this.playerRef, requestFrom(ALLOWED_IP)))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * <b>막힌 요청도 감사에 남는다</b> (R14.5).
	 *
	 * <p>통과한 것만 남기면 감사 로그가 <b>"정상 운영 일지"</b>가 된다 — 허용목록 밖에서 온
	 * 요청이야말로 남아야 할 기록이다.
	 */
	@Test
	void R14_5_a_denied_attempt_is_recorded() {
		UUID userId = givenUser(UserRole.USER);

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_CIDR)).requireRoleAndNetwork(this.playerRef,
				requestFrom(OTHER_IP))).isInstanceOf(ApiException.class);

		verify(this.audit).record(eq(userId), eq(AdminAccessGuard.DENIED_ACTION), eq("admin"), any(),
				any(), any());
	}

	/** 모르는 회원은 감사에 남길 대상이 없다 — {@code admin_user_id} 가 NOT NULL 이다. */
	@Test
	void R14_5_an_unknown_player_ref_is_denied_without_an_audit_row() {
		given(this.users.findByPlayerRef(this.playerRef)).willReturn(Optional.empty());

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_CIDR)).requireRoleAndNetwork(this.playerRef,
				requestFrom(ALLOWED_IP))).isInstanceOf(ApiException.class);
		verify(this.audit, never()).record(any(), any(), any(), any(), any(), any());
	}

	/** 대역 안의 다른 주소도 통과한다 — 운영 접속은 단일 주소가 아니다. */
	@Test
	void SEC4_any_address_inside_the_range_passes() {
		UUID userId = givenUser(UserRole.ADMIN);

		assertThat(guardWith(List.of(ALLOWED_CIDR)).requireRoleAndNetwork(this.playerRef,
				requestFrom("203.0.113.200"))).isEqualTo(userId);
	}

	/** 잘못 적은 대역은 <b>모두 허용</b>이 아니다 — 설정 오류가 문을 열면 안 된다. */
	@Test
	void SEC4_a_malformed_range_does_not_open_the_door() {
		givenUser(UserRole.ADMIN);

		assertThatThrownBy(() -> guardWith(List.of("not-a-cidr")).requireRoleAndNetwork(this.playerRef,
				requestFrom(ALLOWED_IP))).isInstanceOf(ApiException.class);
	}

	/** 셋이 다 맞으면 통과한다 — 승격까지 있어야 {@code requireAdmin} 이 열린다. */
	@Test
	void SEC4_all_three_conditions_together_pass() {
		UUID userId = givenUser(UserRole.ADMIN);
		String stepUp = this.tokens.issueAdminStepUp(this.playerRef).token();

		assertThat(guardWith(List.of(ALLOWED_CIDR)).requireAdmin(this.playerRef,
				requestFrom(ALLOWED_IP, stepUp))).isEqualTo(userId);
	}

	/**
	 * <b>승격이 없으면 역할과 IP 가 맞아도 막힌다.</b>
	 *
	 * <p>이것이 없으면 세 조건이라고 적어 놓고 <b>둘만 요구하는</b> 상태가 된다 — 관리자 계정
	 * 하나가 새면 허용목록 안에서 그대로 열린다.
	 */
	@Test
	void SEC4_without_a_step_up_an_admin_is_still_denied() {
		givenUser(UserRole.ADMIN);

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_CIDR)).requireAdmin(this.playerRef,
				requestFrom(ALLOWED_IP)))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	/** <b>액세스 토큰으로는 승격이 되지 않는다.</b> 용도를 나누지 않으면 로그인이 곧 관리자다. */
	@Test
	void SEC4_an_access_token_is_not_a_step_up() {
		givenUser(UserRole.ADMIN);
		String access = this.tokens.issue(this.playerRef).accessToken();

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_CIDR)).requireAdmin(this.playerRef,
				requestFrom(ALLOWED_IP, access))).isInstanceOf(ApiException.class);
	}

	/**
	 * <b>남의 승격은 내 요청을 통과시키지 못한다.</b>
	 *
	 * <p>주인을 보지 않으면 승격이 계정이 아니라 <b>조직 전체</b>에 붙는 것이 된다.
	 */
	@Test
	void SEC4_a_step_up_issued_to_someone_else_is_rejected() {
		givenUser(UserRole.ADMIN);
		String otherStepUp = this.tokens.issueAdminStepUp(UUID.randomUUID()).token();

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_CIDR)).requireAdmin(this.playerRef,
				requestFrom(ALLOWED_IP, otherStepUp))).isInstanceOf(ApiException.class);
	}

	/** 위조된 값도 403 이다 — 401 로 답하면 <b>토큰만 고치면 된다</b>는 신호가 된다. */
	@Test
	void SEC4_a_forged_step_up_is_rejected() {
		givenUser(UserRole.ADMIN);

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_CIDR)).requireAdmin(this.playerRef,
				requestFrom(ALLOWED_IP, "not-a-token")))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	/** 승격이 없어 막힌 것도 감사에 남는다 (R14.5). */
	@Test
	void R14_5_a_missing_step_up_is_recorded() {
		UUID userId = givenUser(UserRole.ADMIN);

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_CIDR)).requireAdmin(this.playerRef,
				requestFrom(ALLOWED_IP))).isInstanceOf(ApiException.class);

		verify(this.audit).record(eq(userId), eq(AdminAccessGuard.DENIED_ACTION), eq("admin"), any(),
				any(), any());
	}

	/** <b>IP 원문이 감사로 넘어가지 않는다</b> (§12) — 해시만 간다. */
	@Test
	void S12_the_raw_ip_never_reaches_the_audit() {
		UUID userId = givenUser(UserRole.ADMIN);

		guardWith(List.of(ALLOWED_CIDR)).recordAction(userId, "admin.debug.read", "session",
				UUID.randomUUID(), java.util.Map.of(), requestFrom(ALLOWED_IP));

		org.mockito.ArgumentCaptor<String> ipHash = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(this.audit).record(any(), any(), any(), any(), any(), ipHash.capture());
		assertThat(ipHash.getValue()).isNotEqualTo(ALLOWED_IP).hasSize(64);
	}

	private UUID givenUser(UserRole role) {
		User user = User.register(this.playerRef, null, NOW);
		UUID userId = UUID.randomUUID();
		setField(user, "id", userId);
		setField(user, "role", role);
		given(this.users.findByPlayerRef(this.playerRef)).willReturn(Optional.of(user));
		return userId;
	}

	private static MockHttpServletRequest requestFrom(String ip) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(ip);
		return request;
	}

	private MockHttpServletRequest requestFrom(String ip, String stepUpToken) {
		MockHttpServletRequest request = requestFrom(ip);
		request.addHeader(AdminAccessGuard.STEP_UP_HEADER, stepUpToken);
		return request;
	}

	/** 역할 승격 경로를 프로덕션 코드에 만들지 않기 위해서다 (S-11). */
	private static void setField(User user, String name, Object value) {
		try {
			java.lang.reflect.Field field = User.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(user, value);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
