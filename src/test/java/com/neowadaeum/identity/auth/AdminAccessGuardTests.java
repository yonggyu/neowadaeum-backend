package com.neowadaeum.identity.auth;

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

	private static final String ALLOWED_IP = "203.0.113.10";

	private static final String OTHER_IP = "203.0.113.99";

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	private final UserRepository users = mock(UserRepository.class);

	private final AdminAuditRecorder audit = mock(AdminAuditRecorder.class);

	private final UUID playerRef = UUID.randomUUID();

	private AdminAccessGuard guardWith(List<String> allowedIps) {
		return new AdminAccessGuard(this.users, new AdminAccessProperties(allowedIps), this.audit);
	}

	/** 셋이 다 맞으면 통과하고, 행위자의 {@code user.id} 가 나온다 (R14.5). */
	@Test
	void S4_an_admin_from_an_allowed_ip_passes() {
		UUID userId = givenUser(UserRole.ADMIN);

		assertThat(guardWith(List.of(ALLOWED_IP)).requireAdmin(this.playerRef, requestFrom(ALLOWED_IP)))
				.isEqualTo(userId);
		verify(this.audit, never()).record(any(), any(), any(), any(), any(), any());
	}

	/** <b>역할이 없으면 허용목록 안이라도 막힌다.</b> */
	@Test
	void S4_a_normal_user_is_denied_even_from_an_allowed_ip() {
		givenUser(UserRole.USER);

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_IP)).requireAdmin(this.playerRef,
				requestFrom(ALLOWED_IP)))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	/** <b>허용목록 밖이면 관리자라도 막힌다.</b> */
	@Test
	void S4_an_admin_from_another_ip_is_denied() {
		givenUser(UserRole.ADMIN);

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_IP)).requireAdmin(this.playerRef,
				requestFrom(OTHER_IP)))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * <b>허용목록이 비어 있으면 아무도 통과하지 못한다.</b>
	 *
	 * <p>비어 있음을 "제한 없음"으로 읽으면 <b>설정 누락이 전면 허용이 된다.</b>
	 */
	@Test
	void S4_an_empty_allowlist_denies_everyone() {
		givenUser(UserRole.ADMIN);

		assertThatThrownBy(() -> guardWith(List.of()).requireAdmin(this.playerRef, requestFrom(ALLOWED_IP)))
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

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_IP)).requireAdmin(this.playerRef,
				requestFrom(OTHER_IP))).isInstanceOf(ApiException.class);

		verify(this.audit).record(eq(userId), eq(AdminAccessGuard.DENIED_ACTION), eq("admin"), any(),
				any(), any());
	}

	/** 모르는 회원은 감사에 남길 대상이 없다 — {@code admin_user_id} 가 NOT NULL 이다. */
	@Test
	void R14_5_an_unknown_player_ref_is_denied_without_an_audit_row() {
		given(this.users.findByPlayerRef(this.playerRef)).willReturn(Optional.empty());

		assertThatThrownBy(() -> guardWith(List.of(ALLOWED_IP)).requireAdmin(this.playerRef,
				requestFrom(ALLOWED_IP))).isInstanceOf(ApiException.class);
		verify(this.audit, never()).record(any(), any(), any(), any(), any(), any());
	}

	/** <b>IP 원문이 감사로 넘어가지 않는다</b> (§12) — 해시만 간다. */
	@Test
	void S12_the_raw_ip_never_reaches_the_audit() {
		UUID userId = givenUser(UserRole.ADMIN);

		guardWith(List.of(ALLOWED_IP)).recordAction(userId, "admin.debug.read", "session",
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
