package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.domain.AuthorProfile;
import com.neowadaeum.catalog.repository.AuthorProfileRepository;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * #262 — <b>로그인 상태를 복원할 경로가 있다.</b>
 *
 * <p>{@code /api/v1/me} 에는 {@code DELETE} 하나뿐이었다. 클라이언트는 토큰을 메모리에만 두므로
 * (그러지 않으면 XSS 하나로 토큰이 나간다) 새로고침하면 무엇을 들고 있는지 알 수 없고, 물어볼
 * 곳이 없으면 <b>"로그인 유지"는 구현할 수 없다.</b>
 *
 * <p>여기서 못박는 것은 넷이다 — <b>토큰이 없으면 401</b>, <b>내 것만 나온다</b>, <b>식별정보가
 * 한 조각도 나가지 않는다</b>, <b>탈퇴한 계정은 그 사실을 본다</b>.
 */
class MyAccountApiIntegrationTests extends ContainerTestBase {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private AuthorProfileRepository profiles;

	private final List<UUID> createdUsers = new ArrayList<>();

	private final List<UUID> createdProfiles = new ArrayList<>();

	/** 내가 만든 것만 치운다 — 컨테이너는 한 벌이다. */
	@AfterEach
	void clear() {
		this.createdUsers.forEach(this.users::deleteById);
		this.createdUsers.clear();
		this.createdProfiles.forEach(this.profiles::deleteById);
		this.createdProfiles.clear();
	}

	/**
	 * <b>로그인 여부는 상태 코드로 답한다</b> (#262).
	 *
	 * <p>토큰이 없으면 401 이다. 본문에 {@code isLoggedIn} 을 두지 않는 이유가 이것이다 —
	 * 두 코드가 이미 답이고, 필드를 만들면 <b>인증되지 않은 요청에도 200 을 주는 경로</b>가 된다.
	 */
	@Test
	void Issue262_reading_me_without_a_token_is_unauthenticated() throws Exception {
		this.mockMvc.perform(get("/api/v1/me"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
	}

	/**
	 * <b>내 것만 나온다.</b> 조회 대상을 요청이 고를 수 없으므로 남의 계정에 닿을 자리가 없다 —
	 * 누구인지는 토큰이 정한다.
	 */
	@Test
	void Issue262_me_answers_with_my_own_account_not_someone_elses() throws Exception {
		UUID mine = givenMemberWithProfile("달빛서점");
		givenMemberWithProfile("남의이름");

		Map<String, Object> body = getMeAs(mine);

		assertThat(body).containsEntry("displayName", "달빛서점");
	}

	/** 표시명은 <b>선택</b>이다 — 프로필이 없으면 키를 생략하지 않고 {@code null} 로 온다. */
	@Test
	void Issue262_a_member_without_a_profile_gets_a_null_display_name() throws Exception {
		UUID playerRef = givenMember();

		Map<String, Object> body = getMeAs(playerRef);

		assertThat(body).containsKey("displayName");
		assertThat(body.get("displayName")).isNull();
	}

	/**
	 * <b>식별정보가 한 조각도 나가지 않는다</b> (§13-7, I-3, R12.1).
	 *
	 * <p>필드 하나씩 없는지 보는 것으로는 부족하다 — 그 방식은 <b>다음에 늘어나는 필드를 잡지
	 * 못한다.</b> 그래서 <b>필드 집합을 통째로</b> 대조한다. 여기에 무엇이든 더하려면 이 테스트를
	 * 고쳐야 하고, 그때 무엇을 내보내는지가 리뷰에 보인다.
	 */
	@Test
	void Issue262_me_carries_exactly_three_fields_and_no_identifiers() throws Exception {
		UUID playerRef = givenMemberWithProfile("달빛서점");

		Map<String, Object> body = getMeAs(playerRef);

		assertThat(body.keySet()).containsExactlyInAnyOrder("displayName", "role", "status");
		assertThat(body.values()).doesNotContain(playerRef.toString());
	}

	/**
	 * <b>탈퇴한 계정은 그 사실을 본다</b> (R12.5).
	 *
	 * <p>탈퇴는 상태이고 파기 배치가 돌기 전까지 <b>이미 발급된 액세스 토큰은 만료 전까지
	 * 살아 있다</b> — 재발급만 막힌다. 그 사이에 이 경로가 {@code active} 를 돌려주면 화면은
	 * <b>아무 일도 없었던 것처럼</b> 보인다.
	 */
	@Test
	void R12_5_a_withdrawn_account_sees_its_status() throws Exception {
		UUID playerRef = givenMember();
		this.mockMvc.perform(delete("/api/v1/me").with(asPlayer(playerRef)));

		Map<String, Object> body = getMeAs(playerRef);

		assertThat(body).containsEntry("status", "withdrawn");
	}

	/** 가입으로 얻는 역할은 {@code user} 하나다 (R14.6, S-4). */
	@Test
	void R14_6_a_new_member_is_a_plain_user() throws Exception {
		UUID playerRef = givenMember();

		assertThat(getMeAs(playerRef)).containsEntry("role", "user");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getMeAs(UUID playerRef) throws Exception {
		String json = this.mockMvc.perform(get("/api/v1/me").with(asPlayer(playerRef)))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return JSON.readValue(json, Map.class);
	}

	private UUID givenMember() {
		UUID playerRef = UUID.randomUUID();
		User user = this.users.saveAndFlush(User.register(playerRef, LocalDate.of(2000, 1, 1), Instant.now()));
		this.createdUsers.add(user.getId());
		return playerRef;
	}

	private UUID givenMemberWithProfile(String displayName) {
		UUID playerRef = givenMember();
		this.profiles.saveAndFlush(AuthorProfile.of(playerRef, displayName, Instant.now()));
		this.createdProfiles.add(playerRef);
		return playerRef;
	}
}
