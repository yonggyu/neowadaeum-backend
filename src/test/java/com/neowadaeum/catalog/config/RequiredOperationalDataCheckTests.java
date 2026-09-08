package com.neowadaeum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.spi.ConsentTerm;
import com.neowadaeum.common.spi.ConsentTermsQuery;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * <b>운영 데이터 없이 뜬 컨텍스트를 관찰한다</b> (§7.3 · §13-89, 이슈 #426).
 *
 * <p>이 이슈의 본체다. 값이 없어도 부팅은 <b>성공했고</b>, 그 사실은 사용자의 첫 요청에서만
 * 드러났다. 다른 통합 테스트들은 각자 {@code @BeforeEach} 에서 {@code service_config} 를 채우므로
 * <b>값 없이 뜬 컨텍스트를 보는 테스트가 하나도 없었다.</b> 그 자리를 여기서 메운다.
 *
 * <p><b>실제로 부팅시켜 본다.</b> {@code ApplicationRunner} 는 컨텍스트 새로고침이 아니라
 * {@code SpringApplication.run} 이 부르므로, 컨텍스트만 띄우는 러너로는 이 동작이 돌지 않는다 —
 * 애노테이션이 붙어 있다는 확인으로 갈음하지 않는다.
 *
 * <p><b>컨테이너가 필요 없다</b> (ADR-0001). 조회는 계약({@code common/spi})으로 대신한다 — 여기서
 * 보는 것은 <b>값이 없을 때 기동이 어떻게 되는가</b>이지 값이 DB 에서 오는 경로가 아니다.
 */
class RequiredOperationalDataCheckTests {

	/** 설정된 문구. <b>실패 메시지에 이것이 실리면 안 된다</b> (S-3). */
	private static final String NOTICE_TEXT = "이 이야기는 AI가 생성합니다.";

	/**
	 * <b>§7.3 — {@code prod} 는 필수 운영 데이터 없이 기동을 끝내지 않는다.</b>
	 *
	 * <p>이 자리가 없던 동안 서버는 정상 부팅했고 {@code readiness} 도 {@code UP} 이었다.
	 */
	@Test
	void S7_3_prod_does_not_finish_booting_without_the_required_operational_data() {
		assertThatThrownBy(() -> boot(NothingConfigured.class, "prod"))
				.isInstanceOf(IllegalStateException.class);
	}

	/**
	 * <b>무엇이 없는지가 운영자에게 드러난다</b> — 그리고 <b>한 번에 전부</b> 드러난다.
	 *
	 * <p>첫 번째에서 멈추면 하나 넣고 다시 띄우는 일을 그 수만큼 반복하게 된다.
	 */
	@Test
	void S13_89_the_failure_names_every_missing_datum() {
		assertThatThrownBy(() -> boot(NothingConfigured.class, "prod"))
				.hasMessageContaining("ai.notice")
				.hasMessageContaining("consent.terms.tos")
				.hasMessageContaining("consent.terms.privacy");
	}

	/**
	 * <b>SEC3 — 실패가 값을 싣지 않는다.</b>
	 *
	 * <p>"자리 이름이 있다"만 단언하면 값이 함께 새어도 통과한다. 고지 문구는 설정돼 있고 약관
	 * 판본만 없는 상태로 세워, <b>설정된 값이 메시지로 흘러나오지 않는지</b>를 함께 본다.
	 */
	@Test
	void SEC3_the_failure_carries_no_configured_value() {
		assertThatThrownBy(() -> boot(OnlyNoticeConfigured.class, "prod"))
				.hasMessageContaining("consent.terms.tos")
				.hasMessageNotContaining(NOTICE_TEXT);
	}

	/** 값이 전부 있으면 {@code prod} 도 그대로 뜬다 — 검사는 없을 때만 걸린다. */
	@Test
	void S13_89_prod_boots_when_every_required_datum_is_configured() {
		try (ConfigurableApplicationContext context = boot(EverythingConfigured.class, "prod")) {
			assertThat(context.isRunning()).isTrue();
		}
	}

	/**
	 * <b>{@code dev} 는 세우지 않는다.</b>
	 *
	 * <p>로컬에서 부팅을 세우면 매번 손으로 행을 넣어야 하고, 그 값을 어디서 얻는지가 다시 문제가
	 * 된다. {@code dev} 의 자리표시는 {@link DevServiceConfigSeed} 가 채운다 — 그 빈이 없는 이
	 * 테스트에서도 <b>기동은 성공해야 한다.</b>
	 */
	@Test
	void S13_89_a_non_prod_profile_boots_and_only_records_the_gap() {
		try (ConfigurableApplicationContext context = boot(NothingConfigured.class, "dev")) {
			assertThat(context.isRunning()).isTrue();
		}
	}

	/**
	 * <b>프로파일을 하나도 지정하지 않으면 세우지 않는다.</b>
	 *
	 * <p>{@code "!prod"} 계열 표현식이 참이 되는 자리이며(#47), 여기서 부팅을 세우면 프로파일을
	 * 빠뜨린 인스턴스가 원인 없이 죽는다. 검사는 <b>{@code prod} 라고 말한 곳에서만</b> 판정한다.
	 */
	@Test
	void S13_89_no_active_profile_does_not_stop_the_boot() {
		try (ConfigurableApplicationContext context = boot(NothingConfigured.class)) {
			assertThat(context.isRunning()).isTrue();
		}
	}

	/**
	 * <p><b>{@code spring.config.name} 을 비껴 둔다</b> — 로컬의 {@code application.yml} 이 이
	 * 컨텍스트에 끌려오면 테스트가 그 파일의 존재에 따라 갈린다.
	 */
	private static ConfigurableApplicationContext boot(Class<?> configuration, String... profiles) {
		return new SpringApplicationBuilder(configuration)
				.web(WebApplicationType.NONE)
				.bannerMode(Banner.Mode.OFF)
				.profiles(profiles)
				.properties("spring.config.name=required-operational-data-check-tests",
						"spring.main.register-shutdown-hook=false")
				.run();
	}

	@Configuration(proxyBeanMethods = false)
	@Import(RequiredOperationalDataCheck.class)
	static class NothingConfigured {

		@Bean
		AiNoticeQuery notices() {
			return Optional::empty;
		}

		@Bean
		ConsentTermsQuery terms() {
			return consentType -> Optional.empty();
		}

	}

	@Configuration(proxyBeanMethods = false)
	@Import(RequiredOperationalDataCheck.class)
	static class OnlyNoticeConfigured {

		@Bean
		AiNoticeQuery notices() {
			return () -> Optional.of(new AiNotice("2026-07-21", NOTICE_TEXT));
		}

		@Bean
		ConsentTermsQuery terms() {
			return consentType -> Optional.empty();
		}

	}

	@Configuration(proxyBeanMethods = false)
	@Import(RequiredOperationalDataCheck.class)
	static class EverythingConfigured {

		@Bean
		AiNoticeQuery notices() {
			return () -> Optional.of(new AiNotice("2026-07-21", NOTICE_TEXT));
		}

		@Bean
		ConsentTermsQuery terms() {
			return consentType -> Optional.of(new ConsentTerm("v1.0", null));
		}

	}

}
