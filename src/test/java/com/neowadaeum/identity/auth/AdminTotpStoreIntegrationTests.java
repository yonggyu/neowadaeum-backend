package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.identity.domain.AdminTotp;
import com.neowadaeum.identity.repository.AdminTotpRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-40 — TOTP 등록이 앉을 자리 (V6, R14.6).
 *
 * <p>확인 여부와 재사용 기록이 <b>실제로 저장되는지</b>를 본다. 두 값은 메모리에서만 맞으면
 * 의미가 없다 — 재시작 뒤에도 같은 코드가 두 번 통하지 않아야 한다.
 */
class AdminTotpStoreIntegrationTests extends ContainerTestBase {

	@Autowired
	private AdminTotpRepository registrations;

	@Autowired
	private AdminTotpCipher cipher;

	@AfterEach
	void clear() {
		this.registrations.deleteAll();
	}

	/** 등록 직후는 <b>확인 전</b>이다. 그 상태로 문이 열려서는 안 되므로 구분이 남아야 한다. */
	@Test
	void R14_6_a_new_registration_starts_unconfirmed() {
		UUID userId = UUID.randomUUID();

		this.registrations.save(AdminTotp.enroll(userId, this.cipher.wrap(secret()), Instant.now()));

		assertThat(this.registrations.findById(userId)).get().satisfies(saved -> {
			assertThat(saved.isConfirmed()).isFalse();
			assertThat(saved.getLastUsedStep()).isNull();
			assertThat(saved.getCreatedAt()).isNotNull();
		});
	}

	/** 확정과 통과 스텝이 함께 남는다. */
	@Test
	void R14_6_confirming_records_the_used_step() {
		UUID userId = UUID.randomUUID();
		this.registrations.saveAndFlush(
				AdminTotp.enroll(userId, this.cipher.wrap(secret()), Instant.now()));

		AdminTotp registration = this.registrations.findById(userId).orElseThrow();
		registration.confirm(1_000L, Instant.now());
		this.registrations.saveAndFlush(registration);

		assertThat(this.registrations.findById(userId)).get().satisfies(saved -> {
			assertThat(saved.isConfirmed()).isTrue();
			assertThat(saved.getLastUsedStep()).isEqualTo(1_000L);
			assertThat(saved.hasUsed(1_000L)).isTrue();
			assertThat(saved.hasUsed(1_001L)).isFalse();
		});
	}

	/**
	 * <b>비밀을 갈면 확인과 재사용 기록이 함께 되돌아간다.</b> 옛 확인이 남으면 확인하지 않은
	 * 비밀이 곧바로 유효해지고, 옛 스텝이 남으면 새 인증기의 첫 코드가 이미 쓴 것으로 몰린다.
	 */
	@Test
	void R14_6_replacing_the_secret_resets_confirmation_and_replay_state() {
		UUID userId = UUID.randomUUID();
		AdminTotp registration = AdminTotp.enroll(userId, this.cipher.wrap(secret()), Instant.now());
		registration.confirm(1_000L, Instant.now());
		this.registrations.saveAndFlush(registration);

		registration.replaceSecret(this.cipher.wrap(secret()), Instant.now());
		this.registrations.saveAndFlush(registration);

		assertThat(this.registrations.findById(userId)).get().satisfies(saved -> {
			assertThat(saved.isConfirmed()).isFalse();
			assertThat(saved.getLastUsedStep()).isNull();
			assertThat(saved.hasUsed(1_000L)).isFalse();
		});
	}

	/** <b>저장된 것은 암호문이다</b> (S-4). 컬럼을 그대로 읽어 비밀이 나오면 안 된다. */
	@Test
	void S4_the_stored_secret_is_not_readable() {
		UUID userId = UUID.randomUUID();
		byte[] secret = secret();

		this.registrations.saveAndFlush(
				AdminTotp.enroll(userId, this.cipher.wrap(secret), Instant.now()));

		String stored = this.registrations.findById(userId).orElseThrow().getSecretEnc();
		assertThat(stored)
				.doesNotContain(Base32.encode(secret))
				.doesNotContain(java.util.Base64.getEncoder().encodeToString(secret));
		assertThat(this.cipher.unwrap(stored)).isEqualTo(secret);
	}

	/** 테스트 비밀이다. 어떤 실제 등록에도 쓰이지 않는다 (S-11). */
	private byte[] secret() {
		byte[] material = new byte[20];
		java.util.Arrays.fill(material, (byte) 7);
		return material;
	}
}
