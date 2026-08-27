package com.neowadaeum.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.identity.repository.OauthIdentityRepository;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * B-07(1/2) — {@code user} · {@code oauth_identity} 가 {@code identity} 스키마에 실제로 매핑되는지.
 *
 * <p>매핑 자체는 {@code hibernate.hbm2ddl.auto=validate} 가 부팅에서 이미 검증한다. 여기서 보는 것은
 * 그다음이다 — <b>값이 왕복하는가</b>, 그리고 <b>DB 가 규칙을 실제로 거부하는가.</b>
 *
 * <p>시각은 마이크로초 이하가 없는 값을 쓴다. {@code timestamptz} 의 정밀도가 마이크로초라
 * 나노초를 넣으면 잘려 돌아오고, 그러면 이 테스트는 매핑이 아니라 반올림을 검사하게 된다.
 */
class IdentityMappingTests extends ContainerTestBase {

	private static final Instant NOW = Instant.parse("2026-08-27T04:05:06Z");

	@Autowired
	private UserRepository users;

	@Autowired
	private OauthIdentityRepository oauthIdentities;

	/** §2.2 — 가입 시점의 값이 그대로 돌아온다. 상태 표기는 소문자다. */
	@Test
	void S2_2_user_round_trips_with_its_player_ref_and_birth_date() {
		UUID playerRef = UUID.randomUUID();
		LocalDate birthDate = LocalDate.of(2008, 3, 14);

		User saved = this.users.save(User.register(playerRef, birthDate, NOW));
		User found = this.users.findById(saved.getId()).orElseThrow();

		assertThat(found.getPlayerRef()).isEqualTo(playerRef);
		assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(found.getBirthDate()).isEqualTo(birthDate);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		// 연령 확인은 B-13 이다. 가입 직후에는 비어 있다 (R10.2).
		assertThat(found.getAgeVerifiedAt()).isNull();
	}

	/**
	 * <b>I-3 — 다른 스토어에서 돌아오는 유일한 경로가 실제로 뚫려 있다.</b>
	 *
	 * <p>play·catalog 는 {@code user.id} 를 모른다. {@code playerRef} 로 찾지 못하면 회원을
	 * 되짚을 방법이 아예 없어진다.
	 */
	@Test
	void I3_a_user_is_reachable_by_player_ref_alone() {
		UUID playerRef = UUID.randomUUID();
		User saved = this.users.save(User.register(playerRef, null, NOW));

		assertThat(this.users.findByPlayerRef(playerRef))
				.get()
				.extracting(User::getId)
				.isEqualTo(saved.getId());
	}

	/** §2.1 — {@code player_ref} 는 회원당 1개다. 두 회원이 같은 값을 가질 수 없다. */
	@Test
	void S2_1_player_ref_is_unique_across_users() {
		UUID shared = UUID.randomUUID();
		this.users.saveAndFlush(User.register(shared, null, NOW));

		assertThatThrownBy(() -> this.users.saveAndFlush(User.register(shared, null, NOW)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** §2.2 — 소셜 계정 연결이 왕복한다. 이메일은 해시로만 남는다 (§12). */
	@Test
	void S2_2_oauth_identity_round_trips_without_the_email_itself() {
		User user = this.users.save(User.register(UUID.randomUUID(), null, NOW));

		OauthIdentity saved = this.oauthIdentities.save(
				OauthIdentity.link(user.getId(), OauthProvider.GOOGLE, "sub-1", "hash-1", NOW));
		OauthIdentity found = this.oauthIdentities.findById(saved.getId()).orElseThrow();

		assertThat(found.getUserId()).isEqualTo(user.getId());
		assertThat(found.getProvider()).isEqualTo(OauthProvider.GOOGLE);
		assertThat(found.getSubject()).isEqualTo("sub-1");
		assertThat(found.getEmailHash()).isEqualTo("hash-1");
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
	}

	/**
	 * <b>같은 소셜 계정이 두 회원에 붙지 못한다.</b>
	 *
	 * <p>붙을 수 있으면 로그인이 어느 회원으로도 갈 수 있고, 그때 어느 쪽이 선택되는지는
	 * 조회 순서가 정하게 된다. B-12 가 이 제약 위에서 "찾거나 만든다"를 구현한다.
	 */
	@Test
	void S2_2_the_same_social_account_cannot_belong_to_two_users() {
		User first = this.users.save(User.register(UUID.randomUUID(), null, NOW));
		User second = this.users.save(User.register(UUID.randomUUID(), null, NOW));
		this.oauthIdentities.saveAndFlush(
				OauthIdentity.link(first.getId(), OauthProvider.GOOGLE, "sub-shared", null, NOW));

		assertThatThrownBy(() -> this.oauthIdentities.saveAndFlush(
				OauthIdentity.link(second.getId(), OauthProvider.GOOGLE, "sub-shared", null, NOW)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** B-12 의 로그인 조회. 제약과 같은 축이라 결과는 0 또는 1건이다. */
	@Test
	void B12_login_lookup_finds_the_link_by_provider_and_subject() {
		User user = this.users.save(User.register(UUID.randomUUID(), null, NOW));
		this.oauthIdentities.save(
				OauthIdentity.link(user.getId(), OauthProvider.APPLE, "sub-2", null, NOW));

		assertThat(this.oauthIdentities.findByProviderAndSubject(OauthProvider.APPLE, "sub-2"))
				.get()
				.extracting(OauthIdentity::getUserId)
				.isEqualTo(user.getId());
		assertThat(this.oauthIdentities.findByProviderAndSubject(OauthProvider.GOOGLE, "sub-2"))
				.isEmpty();
	}
}
