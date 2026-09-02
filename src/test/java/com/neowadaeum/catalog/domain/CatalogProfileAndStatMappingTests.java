package com.neowadaeum.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.repository.AuthorProfileRepository;
import com.neowadaeum.catalog.repository.EndingStatRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * B-08(2/2) — {@code author_profile} · {@code ending_stat} 이 {@code catalog} 스키마에 매핑되는지.
 *
 * <p>둘 다 <b>사람에 대한 값을 최소로 든다.</b> 표시명은 공개 문구이고 도달률은 집계다.
 * 여기서 확인하는 것은 그 최소가 실제로 지켜지는가와, DB 가 규칙을 거부하는가다.
 *
 * <p>시드 작품을 빌려 쓴다 — {@code ending_stat.story_id} 에 FK 가 걸려 있다.
 */
class CatalogProfileAndStatMappingTests extends ContainerTestBase {

	/** S-4 시드가 넣은 공식 작품. {@code CatalogSeedTests} 와 같은 값이다. */
	private static final UUID SEED_STORY = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final Instant NOW = Instant.parse("2026-08-27T04:05:06Z");

	@Autowired
	private AuthorProfileRepository profiles;

	@Autowired
	private EndingStatRepository stats;

	@AfterEach
	void clear() {
		this.profiles.deleteAll();
		this.stats.deleteAll();
	}

	/** §13-7 — 표시명이 {@code playerRef} 를 키로 왕복한다. */
	@Test
	void S13_7_author_profile_round_trips_keyed_by_player_ref() {
		UUID playerRef = UUID.randomUUID();

		this.profiles.save(AuthorProfile.of(playerRef, "달빛서점", NOW));
		AuthorProfile found = this.profiles.findById(playerRef).orElseThrow();

		assertThat(found.getDisplayName()).isEqualTo("달빛서점");
		assertThat(found.getUpdatedAt()).isEqualTo(NOW);
	}

	/**
	 * <b>I-3 — 이 표에는 {@code user.id} 를 담을 자리가 없다.</b>
	 *
	 * <p>매핑된 속성이 셋뿐이라는 것을 메타모델에 물어 확인한다. 컬럼이 하나 늘면 여기서
	 * 먼저 드러난다 — 회원 식별정보가 catalog 로 새는 경로는 대개 "편의상 하나 더"로 시작한다.
	 */
	@Test
	void I3_author_profile_holds_nothing_that_identifies_a_member() {
		assertThat(AuthorProfile.class.getDeclaredFields())
				.extracting(java.lang.reflect.Field::getName)
				.containsExactlyInAnyOrder("playerRef", "displayName", "updatedAt");
	}

	/** 닉네임 갱신은 새 행이 아니라 같은 행의 변경이다 — {@code playerRef} 가 PK 이기 때문이다. */
	@Test
	void S13_7_renaming_updates_the_same_row() {
		UUID playerRef = UUID.randomUUID();
		AuthorProfile profile = this.profiles.save(AuthorProfile.of(playerRef, "이전이름", NOW));

		profile.rename("새이름", NOW.plusSeconds(60));
		this.profiles.saveAndFlush(profile);

		assertThat(this.profiles.count()).isEqualTo(1);
		assertThat(this.profiles.findById(playerRef).orElseThrow())
				.extracting(AuthorProfile::getDisplayName, AuthorProfile::getUpdatedAt)
				.containsExactly("새이름", NOW.plusSeconds(60));
	}

	/** 목록 화면은 작성자를 작품 수만큼 묻지 않는다 — 한 번에 읽는다 (B-15). */
	@Test
	void B15_profiles_are_readable_in_one_batch() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		// 두 글자 이상이어야 한다 (#287). 한 글자 이름은 도메인이 거절한다.
		this.profiles.save(AuthorProfile.of(first, "가나", NOW));
		this.profiles.save(AuthorProfile.of(second, "다라", NOW));

		assertThat(this.profiles.findByPlayerRefIn(List.of(first, second, UUID.randomUUID())))
				.extracting(AuthorProfile::getDisplayName)
				.containsExactlyInAnyOrder("가나", "다라");
	}

	/**
	 * <b>§2.6 — 집계 키가 {@code (storyId, endingNo)} 다.</b>
	 *
	 * <p>{@code endingId} 로 셌다면 버전 발행 때마다 행이 새로 생겨 도달률이 0 부터 다시
	 * 시작한다. 같은 작품·같은 엔딩 번호로 두 번 저장해도 <b>행이 하나</b>인 것이 그 성질이다.
	 */
	@Test
	void S2_6_the_aggregate_key_is_story_and_ending_number() {
		this.stats.save(EndingStat.of(SEED_STORY, 1, 10, 100, NOW));
		this.stats.save(EndingStat.of(SEED_STORY, 1, 20, 200, NOW.plusSeconds(60)));

		assertThat(this.stats.findByStoryId(SEED_STORY)).hasSize(1);
		assertThat(this.stats.findById(new EndingStat.Key(SEED_STORY, 1)).orElseThrow())
				.extracting(EndingStat::getReachedCount, EndingStat::getTotalCompletedCount)
				.containsExactly(20L, 200L);
	}

	/** 한 작품의 엔딩별 행이 나란히 선다 (B-16 · B-39). */
	@Test
	void S2_6_each_ending_of_a_story_has_its_own_row() {
		this.stats.save(EndingStat.of(SEED_STORY, 1, 10, 100, NOW));
		this.stats.save(EndingStat.of(SEED_STORY, 2, 30, 100, NOW));

		assertThat(this.stats.findByStoryId(SEED_STORY))
				.extracting(EndingStat::getEndingNo)
				.containsExactlyInAnyOrder(1, 2);
	}

	/**
	 * <b>분모가 분자보다 작을 수 없다.</b>
	 *
	 * <p>배치가 잘못 세면 도달률이 100% 를 넘는다. 엔티티가 먼저 걸러 <b>원인이 배치라는 것</b>이
	 * 드러나게 하고, DB CHECK 가 같은 규칙을 마지막에 한 번 더 본다.
	 */
	@Test
	void S2_6_reached_count_cannot_exceed_the_total() {
		assertThatThrownBy(() -> EndingStat.of(SEED_STORY, 1, 101, 100, NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** §5.3 — 존재하지 않는 작품의 집계를 만들 수 없다. FK 가 catalog 안이므로 DB 가 막는다. */
	@Test
	void S5_3_a_stat_for_an_unknown_story_is_rejected_by_the_database() {
		List<EndingStat> orphan = List.of(EndingStat.of(UUID.randomUUID(), 1, 0, 0, NOW));

		assertThatThrownBy(() -> this.stats.saveAllAndFlush(orphan))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
