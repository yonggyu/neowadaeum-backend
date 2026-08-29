package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.identity.auth.AuthTokenService;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.domain.UserStatus;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-62(1/2) — <b>탈퇴는 신청이 아니라 차단이다</b> (R12.5).
 *
 * <p>B-61 이 파기 배치를 전부 만들었지만 {@code withdrawn} 으로 바꾸는 경로가 없었다. 그리고
 * 상태만 바꾸는 것으로는 부족하다 — <b>토큰을 계속 회전시킬 수 있으면</b> 탈퇴는 파기 배치가
 * 돌 때까지 아무것도 막지 못한다.
 */
class WithdrawalApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private AuthTokenService issuedTokens;

	private final java.util.List<UUID> createdUsers = new java.util.ArrayList<>();

	/** 내가 만든 회원만 치운다 — 컨테이너는 한 벌이다. */
	@AfterEach
	void clear() {
		this.createdUsers.forEach(this.users::deleteById);
		this.createdUsers.clear();
	}

	/** <b>탈퇴하면 상태가 바뀐다</b> (R12.5). 지워지는 것은 파기 배치의 몫이다 (B-61). */
	@Test
	void R12_5_withdrawing_marks_the_account_withdrawn() throws Exception {
		Member member = givenMember();

		this.mockMvc.perform(delete("/api/v1/me").with(asPlayer(member.playerRef())))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));

		assertThat(this.users.findById(member.userId())).get()
				.satisfies(user -> assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN));
	}

	/**
	 * <b>두 번 눌러도 결과가 같다.</b>
	 *
	 * <p>탈퇴는 되돌릴 수 없으므로 두 번째 호출에 오류를 주면 클라이언트는 <b>탈퇴에 실패했다</b>고
	 * 읽는다 — 그리고 사용자는 이미 나간 계정으로 돌아가려 한다.
	 */
	@Test
	void R12_5_withdrawing_twice_is_still_successful() throws Exception {
		Member member = givenMember();

		this.mockMvc.perform(delete("/api/v1/me").with(asPlayer(member.playerRef())));
		this.mockMvc.perform(delete("/api/v1/me").with(asPlayer(member.playerRef())))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(204));

		assertThat(this.users.findById(member.userId())).get()
				.satisfies(user -> assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN));
	}

	/**
	 * <b>탈퇴한 계정은 토큰을 재발급받지 못한다</b> (R12.5, §13 리프레시 상태).
	 *
	 * <p>이것이 없으면 탈퇴는 <b>다음 로그인부터 적용되는 신청</b>이 된다 — 리프레시가 상태를
	 * 묻지 않으므로 파기 배치가 돌 때까지 서비스를 그대로 쓸 수 있다.
	 */
	@Test
	void R12_5_a_withdrawn_account_cannot_refresh() throws Exception {
		Member member = givenMember();
		String refreshToken = this.issuedTokens.issue(member.playerRef()).refreshToken();
		this.mockMvc.perform(delete("/api/v1/me").with(asPlayer(member.playerRef())));

		this.mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(java.util.Map.of("refreshToken", refreshToken))))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));
	}

	/** 인증 없이는 부를 수 없다 — 남의 계정을 지울 수 있는 경로가 되면 안 된다. */
	@Test
	void R12_5_withdrawal_requires_authentication() throws Exception {
		this.mockMvc.perform(delete("/api/v1/me"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
	}

	private record Member(UUID userId, UUID playerRef) {
	}

	private Member givenMember() {
		UUID playerRef = UUID.randomUUID();
		User user = this.users.saveAndFlush(
				User.register(playerRef, LocalDate.of(2000, 1, 1), Instant.now()));
		this.createdUsers.add(user.getId());
		return new Member(user.getId(), playerRef);
	}
}
