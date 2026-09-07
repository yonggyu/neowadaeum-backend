package com.neowadaeum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * dev 자리표시가 <b>운영에 만들어지지 않고</b>, 만들어진 값은 <b>가짜로 보인다</b> (§13-89, 이슈 #426).
 *
 * <p>#34 · ADR-0004 — dev 전용 경로는 애노테이션이 붙어 있다는 확인이 아니라 <b>빈이 없다는
 * 동작</b>으로 검증한다. 이 사고는 조용하다: 예외가 아니라 <b>자리표시 약관 판본이 운영 동의
 * 이력에 남는</b> 형태로 나타나고, {@code consent_log} 는 append-only 라 지울 수 없다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class DevServiceConfigSeedTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(ServiceConfigRepository.class, () -> mock(ServiceConfigRepository.class))
			.withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
			.withUserConfiguration(DevServiceConfigSeed.class);

	/**
	 * <b>프로파일을 하나도 지정하지 않으면 등록되지 않는다</b> (#47).
	 *
	 * <p>{@code @Profile("!prod")} 는 여기서 <b>참</b>이다 — 차단이 "이름을 빠뜨리지 않는 것"에
	 * 의존하면 언젠가 빠뜨린다.
	 */
	@Test
	void S13_89_no_active_profile_means_no_placeholder_seed() {
		this.runner.run(context -> assertThat(context).doesNotHaveBean(DevServiceConfigSeed.class));
	}

	/** {@code prod} 에서는 만들어지지 않는다. */
	@Test
	void S13_89_the_placeholder_seed_is_absent_under_the_prod_profile() {
		this.runner.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).doesNotHaveBean(DevServiceConfigSeed.class));
	}

	/** {@code prod} 가 다른 프로파일과 함께 켜져도 닫혀 있다. */
	@Test
	void S13_89_prod_stays_closed_even_when_combined_with_dev() {
		this.runner.withPropertyValues("spring.profiles.active=prod,dev")
				.run(context -> assertThat(context).doesNotHaveBean(DevServiceConfigSeed.class));
	}

	/** {@code prod} 아닌 아무 프로파일로는 켜지지 않는다 — {@code "dev & !prod"} 가 갈리는 지점이다. */
	@Test
	void S13_89_an_unrelated_profile_does_not_enable_it() {
		this.runner.withPropertyValues("spring.profiles.active=staging")
				.run(context -> assertThat(context).doesNotHaveBean(DevServiceConfigSeed.class));
	}

	/** 명시적으로 켜는 경로는 {@code dev} 하나다. */
	@Test
	void S13_89_the_dev_profile_is_the_explicit_opt_in() {
		this.runner.withPropertyValues("spring.profiles.active=dev")
				.run(context -> assertThat(context).hasSingleBean(DevServiceConfigSeed.class));
	}

	/**
	 * <b>판본이 진짜와 형식으로 갈린다</b> (§13-51).
	 *
	 * <p>약관 판본은 {@code v<major>.<minor>} 다. 자리표시가 그 형식을 쓰면 진짜와 구분되지 않고,
	 * <b>나중에 형식 검증을 붙일 때 자리표시가 걸리는 것이 옳은 동작</b>이다.
	 */
	@Test
	void S13_89_the_placeholder_version_is_not_shaped_like_a_real_one() {
		for (JsonNode version : versionsOf(seeded())) {
			assertThat(version.asString()).doesNotMatch("^v\\d+\\.\\d+$").isEqualTo("dev-placeholder");
		}
	}

	/**
	 * <b>문구가 표지로 시작한다.</b>
	 *
	 * <p>프로파일 경계가 못 잡아도 사람 눈이 잡는다. 그리고 <b>운영 주소는 들어 있지 않다</b>
	 * (S-11) — 자리표시 약관에는 본문 주소가 없다.
	 */
	@Test
	void SEC11_the_placeholder_text_says_it_is_not_real_and_carries_no_address() {
		List<ServiceConfig> seeded = seeded();
		String notice = valueOf(seeded, "ai.notice");

		assertThat(JSON.readTree(notice).path("text").asString()).startsWith("[개발용 자리표시");
		assertThat(valueOf(seeded, "consent.terms")).doesNotContain("documentUrl").doesNotContain("http");
	}

	/**
	 * <b>이미 있는 값을 덮어쓰지 않는다.</b>
	 *
	 * <p>로컬에 진짜 문구를 넣어 두고 화면을 보는 경우가 있고, 뜰 때마다 덮어쓰면 그 작업이
	 * 조용히 지워진다.
	 */
	@Test
	void S13_89_an_existing_value_is_left_alone() {
		ServiceConfigRepository configs = mock(ServiceConfigRepository.class);
		given(configs.existsById(any())).willReturn(true);

		new DevServiceConfigSeed(configs, Clock.fixed(NOW, ZoneOffset.UTC)).run(null);

		verify(configs, never()).save(any());
	}

	/** 자리표시를 넣고 저장된 행을 돌려준다. */
	private static List<ServiceConfig> seeded() {
		ServiceConfigRepository configs = mock(ServiceConfigRepository.class);
		given(configs.existsById(any())).willReturn(false);

		new DevServiceConfigSeed(configs, Clock.fixed(NOW, ZoneOffset.UTC)).run(null);

		ArgumentCaptor<ServiceConfig> saved = ArgumentCaptor.forClass(ServiceConfig.class);
		verify(configs, times(2)).save(saved.capture());
		return saved.getAllValues();
	}

	private static String valueOf(List<ServiceConfig> seeded, String key) {
		return seeded.stream().filter(config -> config.getConfigKey().equals(key)).findFirst()
				.orElseThrow().getConfigValue();
	}

	/** 두 키가 담은 판본 전부 — 고지 하나와 약관 둘이다. */
	private static List<JsonNode> versionsOf(List<ServiceConfig> seeded) {
		JsonNode notice = JSON.readTree(valueOf(seeded, "ai.notice"));
		JsonNode terms = JSON.readTree(valueOf(seeded, "consent.terms"));
		return List.of(notice.path("version"), terms.path("tos").path("version"),
				terms.path("privacy").path("version"));
	}

}
