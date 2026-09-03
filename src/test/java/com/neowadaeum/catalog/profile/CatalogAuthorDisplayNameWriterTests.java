package com.neowadaeum.catalog.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.neowadaeum.catalog.domain.AuthorProfile;
import com.neowadaeum.catalog.repository.AuthorProfileRepository;
import com.neowadaeum.common.spi.InvalidDisplayNameException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * #271 — <b>표시명을 만드는 쪽이 지키는 것</b> (§13-7).
 *
 * <p>컨테이너가 필요 없다 (ADR-0001). 여기서 보는 것은 <b>없으면 만들고 있으면 바꾼다</b>,
 * <b>규칙 위반은 저장에 닿지 않는다</b> 둘이다.
 */
class CatalogAuthorDisplayNameWriterTests {

	private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

	private final AuthorProfileRepository profiles = mock(AuthorProfileRepository.class);

	private final CatalogAuthorDisplayNameWriter writer =
			new CatalogAuthorDisplayNameWriter(this.profiles, Clock.fixed(NOW, ZoneOffset.UTC));

	private final UUID playerRef = UUID.randomUUID();

	/** 프로필이 없으면 만든다 — 화면이 "처음인가"를 먼저 묻지 않아도 된다. */
	@Test
	void Issue271_a_member_without_a_profile_gets_one() {
		given(this.profiles.findById(this.playerRef)).willReturn(Optional.empty());

		String stored = this.writer.updateDisplayName(this.playerRef, "달빛서점");

		assertThat(stored).isEqualTo("달빛서점");
		then(this.profiles).should().save(any(AuthorProfile.class));
	}

	/** 있으면 바꾼다. 같은 요청이 두 행을 만들지 않는다 — PK 가 {@code playerRef} 다 (I-3). */
	@Test
	void Issue271_an_existing_profile_is_renamed_in_place() {
		AuthorProfile existing = AuthorProfile.of(this.playerRef, "이전이름", NOW);
		given(this.profiles.findById(this.playerRef)).willReturn(Optional.of(existing));

		this.writer.updateDisplayName(this.playerRef, "달빛서점");

		assertThat(existing.getDisplayName()).isEqualTo("달빛서점");
		then(this.profiles).should(never()).save(any(AuthorProfile.class));
	}

	/**
	 * <b>정규화 결과를 돌려준다</b> — 양끝 공백과 내부 연속 공백이 정리된다 (#287).
	 *
	 * <p>호출자가 입력을 저장된 값이라고 믿지 않게 하는 것이 반환값의 용도다.
	 */
	@Test
	void S13_7_the_normalized_value_is_what_comes_back() {
		given(this.profiles.findById(this.playerRef)).willReturn(Optional.empty());

		assertThat(this.writer.updateDisplayName(this.playerRef, "  달빛  서점 ")).isEqualTo("달빛 서점");
	}

	/**
	 * <b>규칙 위반은 경계를 넘는 타입으로 바뀐다</b> (§13-7).
	 *
	 * <p>{@code IllegalArgumentException} 을 그대로 흘려보내면 identity 는 <b>사용자의 잘못과
	 * 구현의 버그를 구분할 수 없다.</b>
	 */
	@Test
	void S13_7_a_reserved_name_is_rejected_before_it_is_stored() {
		assertThatThrownBy(() -> this.writer.updateDisplayName(this.playerRef, "탈퇴한 사용자"))
				.isInstanceOf(InvalidDisplayNameException.class);

		then(this.profiles).should(never()).save(any(AuthorProfile.class));
	}

	/**
	 * <b>거절 사유에 입력값이 없다</b> (S-3).
	 *
	 * <p>이 문구는 400 응답의 {@code details} 로 나간다. 사용자가 쓴 값이 실리면 그 값이
	 * 클라이언트 로그로 퍼진다.
	 */
	@Test
	void SEC3_the_reason_names_the_rule_not_the_input() {
		assertThatThrownBy(() -> this.writer.updateDisplayName(this.playerRef, "@달빛서점"))
				.isInstanceOf(InvalidDisplayNameException.class)
				.extracting(failure -> ((InvalidDisplayNameException) failure).reason())
				.asString()
				.doesNotContain("달빛서점");
	}
}
