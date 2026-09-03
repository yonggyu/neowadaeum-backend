package com.neowadaeum.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

	// ── 표시명을 쓰는 경로 (#271) ────────────────────────────────

	/**
	 * <b>설정과 변경이 한 경로다</b> (#271, §13-7).
	 *
	 * <p>읽는 곳은 셋인데 쓰는 곳이 없어서 실사용에서 {@code displayName} 이 항상 {@code null}
	 * 이었다. 여기서 못박는 것은 <b>없던 이름이 생기고, 생긴 이름이 바뀐다</b>는 것 — 그 둘이
	 * 같은 요청이며 <b>다시 {@code GET} 해도 같은 값</b>이라는 것이다.
	 */
	@Test
	void Issue271_a_display_name_is_created_and_then_changed_by_the_same_request() throws Exception {
		UUID playerRef = givenMember();
		this.createdProfiles.add(playerRef);

		assertThat(getMeAs(playerRef).get("displayName")).isNull();

		assertThat(patchMeAs(playerRef, "달빛서점")).containsEntry("displayName", "달빛서점");
		assertThat(patchMeAs(playerRef, "새벽서점")).containsEntry("displayName", "새벽서점");

		assertThat(getMeAs(playerRef)).containsEntry("displayName", "새벽서점");
	}

	/**
	 * <b>응답은 저장된 값이다</b> (#287).
	 *
	 * <p>서버가 정규화하므로 보낸 값과 다를 수 있다. 돌려주지 않으면 화면은 <b>자기가 보낸
	 * 값</b>을 저장된 이름이라고 믿는다.
	 */
	@Test
	void S13_7_the_response_carries_the_normalized_name_not_the_one_that_was_sent() throws Exception {
		UUID playerRef = givenMember();
		this.createdProfiles.add(playerRef);

		assertThat(patchMeAs(playerRef, "  달빛  서점 ")).containsEntry("displayName", "달빛 서점");
	}

	/** 규칙에 맞지 않는 이름은 400 이다 (§13-7, #287) — 길이 · 문자 · 정규화가 도메인의 규칙이다. */
	@Test
	void S13_7_a_name_that_breaks_the_rules_is_rejected() throws Exception {
		UUID playerRef = givenMember();

		assertThat(patchMe(playerRef, "가").getResponse().getStatus()).isEqualTo(400);
		assertThat(patchMe(playerRef, "열두글자를넘기는아주긴이름").getResponse().getStatus()).isEqualTo(400);
		assertThat(patchMe(playerRef, "@달빛서점").getResponse().getStatus()).isEqualTo(400);
		assertThat(this.profiles.findById(playerRef)).isEmpty();
	}

	/**
	 * <b>예약된 이름은 거절한다</b> (§13-7).
	 *
	 * <p>{@code "탈퇴한 사용자"} 는 파기 배치가 쓰는 값이다. 사용자가 그것을 고르면 <b>탈퇴한
	 * 계정을 사칭한다.</b> DB 제약이 아니라 <b>사용자 입력이 들어오는 이 자리</b>가 막는다 —
	 * 제약을 걸면 배치가 막히기 때문이다.
	 */
	@Test
	void S13_7_the_name_the_purge_batch_uses_is_reserved() throws Exception {
		UUID playerRef = givenMember();

		assertThat(patchMe(playerRef, "탈퇴한 사용자").getResponse().getStatus()).isEqualTo(400);
		assertThat(this.profiles.findById(playerRef)).isEmpty();
	}

	/**
	 * <b>거절 응답이 입력값을 되돌려 주지 않는다</b> (S-3).
	 *
	 * <p>"있어야 할 것"만 단언하면 값이 새어도 통과하므로 <b>없어야 할 것</b>을 함께 단언한다.
	 */
	@Test
	void SEC3_the_rejection_does_not_echo_the_name_that_was_sent() throws Exception {
		UUID playerRef = givenMember();

		String body = patchMe(playerRef, "@달빛서점").getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("VALIDATION_ERROR").doesNotContain("달빛서점");
	}

	/** 토큰이 없으면 401 이다 — 읽기와 같은 규칙이며, 이름을 바꾸는 쪽이 더 느슨할 이유가 없다. */
	@Test
	void Issue271_changing_a_name_without_a_token_is_unauthenticated() throws Exception {
		this.mockMvc.perform(patch("/api/v1/me")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"displayName\":\"달빛서점\"}"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
	}

	/**
	 * <b>탈퇴한 계정은 이름을 바꾸지 못한다</b> (R12.5).
	 *
	 * <p>액세스 토큰은 탈퇴 후에도 만료 전까지 살아 있다. 막지 않으면 파기 배치가 익명으로
	 * 바꿔 둔 이름을 <b>탈퇴한 계정이 도로 되돌린다.</b>
	 */
	@Test
	void R12_5_a_withdrawn_account_cannot_rename_itself() throws Exception {
		UUID playerRef = givenMember();
		this.mockMvc.perform(delete("/api/v1/me").with(asPlayer(playerRef)));

		assertThat(patchMe(playerRef, "달빛서점").getResponse().getStatus()).isEqualTo(403);
		assertThat(this.profiles.findById(playerRef)).isEmpty();
	}

	/**
	 * <b>중복을 막지 않는다</b> (§13-55).
	 *
	 * <p>{@code author_profile} 에 표시명 유일 제약이 없다 — {@code catalog/V7} 도
	 * {@code catalog/V11} 도 두지 않았다. 이 테스트는 <b>제약이 조용히 생기면 깨지도록</b>
	 * 계약을 못박는다: 유일성을 도입하려면 마이그레이션과 함께 이 자리를 고쳐야 한다.
	 */
	@Test
	void S13_55_two_members_may_hold_the_same_display_name() throws Exception {
		UUID first = givenMember();
		UUID second = givenMember();
		this.createdProfiles.add(first);
		this.createdProfiles.add(second);

		assertThat(patchMeAs(first, "달빛서점")).containsEntry("displayName", "달빛서점");
		assertThat(patchMeAs(second, "달빛서점")).containsEntry("displayName", "달빛서점");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> patchMeAs(UUID playerRef, String displayName) throws Exception {
		String json = patchMe(playerRef, displayName).getResponse().getContentAsString(StandardCharsets.UTF_8);
		return JSON.readValue(json, Map.class);
	}

	private MvcResult patchMe(UUID playerRef, String displayName) throws Exception {
		return this.mockMvc.perform(patch("/api/v1/me").with(asPlayer(playerRef))
						.contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(Map.of("displayName", displayName))))
				.andReturn();
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
