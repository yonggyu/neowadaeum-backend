package com.neowadaeum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * §7.3 — 잘못된 {@code app.datasource.*} 설정에서 <b>부팅이 실패하는지</b>, 그리고 실패가
 * <b>무엇이 잘못됐는지 말하는지</b> 확인한다.
 *
 * <p>DB 가 필요 없다. {@code HikariDataSource} 는 첫 {@code getConnection()} 까지 접속하지 않으므로,
 * 빈 생성만으로 설정 검증 경로를 전부 지나간다. 실제 스키마 분리는 {@link StoreSeparationTests} 가 본다.
 */
class DataSourceConfigurationTests {

	private static final String VALID_URL_TEMPLATE = "jdbc:postgresql://localhost:5432/neowadaeum?currentSchema=%s";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(DataSourceConfiguration.class);

	/** 정상 설정에서는 §5.3 의 DataSource 4개가 만들어진다. */
	@Test
	void S5_3_four_datasources_are_created_from_valid_settings() {
		this.runner.withPropertyValues(allStores()).run(context -> assertThat(context)
				.hasNotFailed()
				.getBeanNames(DataSource.class)
				.containsExactlyInAnyOrder("identityDataSource", "catalogDataSource", "playDataSource",
						"promptLogDataSource"));
	}

	/**
	 * §7.3 — 스토어 블록이 통째로 빠지면 부팅이 실패하고, <b>어느 스토어인지</b>가 실패에 드러난다.
	 *
	 * <p>NPE 로 죽는 것은 fail-fast 이긴 해도 무엇이 비었는지 말하지 않는다.
	 */
	@Test
	void S7_3_missing_store_block_fails_startup_and_names_the_store() {
		this.runner.withPropertyValues(allStoresExcept(StoreSchema.PLAY)).run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.hasStackTraceContaining("play")
				.hasStackTraceContaining(StoreDataSourceProperties.MISSING_STORE));
	}

	/** §7.3 — 빈 값도 마찬가지다. 기본값으로 조용히 뜨지 않는다. */
	@Test
	void S7_3_blank_password_fails_startup() {
		List<String> properties = new ArrayList<>(List.of(allStores()));
		properties.replaceAll(line -> line.startsWith("app.datasource.promptlog.password=")
				? "app.datasource.promptlog.password=" : line);

		this.runner.withPropertyValues(properties.toArray(String[]::new))
				.run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("promptlog"));
	}

	/**
	 * §5.3 — {@code currentSchema} 가 없으면 부팅이 실패한다.
	 *
	 * <p>빠진 채로 뜨면 마이그레이션과 모든 테이블이 조용히 다른 스키마에 만들어진다.
	 */
	@Test
	void S5_3_url_without_current_schema_fails_startup() {
		this.runner.withPropertyValues(withUrl(StoreSchema.CATALOG, "jdbc:postgresql://localhost:5432/neowadaeum"))
				.run(context -> assertThat(context).hasFailed()
						.getFailure()
						.hasStackTraceContaining("currentSchema=catalog"));
	}

	/**
	 * §5.3 회귀 방지 — 접두어만 같은 스키마를 통과시키지 않는다. 그리고 S-11 — 실패에 URL 을 흘리지 않는다.
	 *
	 * <p>{@code contains("currentSchema=play")} 로 검사하면 {@code currentSchema=playground} 가 통과한다.
	 * 이 검사의 목적이 조용한 오배치 차단이므로 부분 일치는 목적과 어긋난다.
	 *
	 * <p><b>단언이 두 겹인 이유.</b> {@code hasStackTraceContaining("currentSchema=play")} 하나만 두면,
	 * 실패 URL 자체가 {@code currentSchema=playground} 를 담고 있으므로 <b>URL 이 통째로 스택트레이스에
	 * 새어 나와도 이 테스트는 통과한다.</b> 이 레포는 공개이고 CI 로그도 공개된다(S-11, S-3). 그래서
	 * "무엇이 있어야 하는가"와 "무엇이 없어야 하는가"를 함께 본다.
	 */
	@Test
	void S5_3_url_with_prefix_matching_schema_fails_startup_without_leaking_the_url() {
		String wrongUrl = "jdbc:postgresql://localhost:5432/neowadaeum?currentSchema=playground";

		this.runner.withPropertyValues(withUrl(StoreSchema.PLAY, wrongUrl)).run(context -> {
			assertThat(context).hasFailed();

			assertThat(stackTraceOf(context.getStartupFailure()))
					.as("어느 프로퍼티가 왜 틀렸는지는 말해야 한다")
					.contains("app.datasource.play.url")
					.contains("currentSchema=play")
					.as("URL 은 접속 정보다. 예외에도 로그에도 남기지 않는다 (S-11, S-3)")
					.doesNotContain(wrongUrl)
					.doesNotContain("localhost:5432")
					.doesNotContain("playground");
		});
	}

	private static String stackTraceOf(Throwable failure) {
		StringWriter buffer = new StringWriter();
		failure.printStackTrace(new PrintWriter(buffer));
		return buffer.toString();
	}

	/**
	 * §5.3 회귀 방지 — {@code search_path} 를 넓히는 목록 표기를 통과시키지 않는다.
	 *
	 * <p>{@code currentSchema=identity,public} 은 드라이버가 받아들이지만, 스키마 경계를 넓히는 것이라
	 * §5.3 이 막으려는 상태 그 자체다.
	 */
	@Test
	void S5_3_url_with_schema_list_fails_startup() {
		this.runner.withPropertyValues(
				withUrl(StoreSchema.IDENTITY, "jdbc:postgresql://localhost:5432/neowadaeum?currentSchema=identity,public"))
				.run(context -> assertThat(context).hasFailed()
						.getFailure()
						.hasStackTraceContaining("currentSchema=identity"));
	}

	/** 뒤에 다른 파라미터가 붙는 정상 형태는 통과해야 한다. 경계 검사가 과하지 않은지 본다. */
	@Test
	void S5_3_url_with_trailing_parameter_is_accepted() {
		this.runner.withPropertyValues(withUrl(StoreSchema.PLAY,
				"jdbc:postgresql://localhost:5432/neowadaeum?currentSchema=play&ApplicationName=neowadaeum"))
				.run(context -> assertThat(context).hasNotFailed());
	}

	private static String[] allStores() {
		return storeProperties(null, null, null);
	}

	private static String[] allStoresExcept(StoreSchema omitted) {
		return storeProperties(omitted, null, null);
	}

	private static String[] withUrl(StoreSchema store, String url) {
		return storeProperties(null, store, url);
	}

	/**
	 * 네 스토어의 프로퍼티를 만든다.
	 *
	 * @param omitted 이 스토어의 블록을 통째로 뺀다 (null 이면 전부 포함)
	 * @param overridden 이 스토어의 url 만 바꾼다 (null 이면 전부 정상값)
	 */
	private static String[] storeProperties(StoreSchema omitted, StoreSchema overridden, String url) {
		List<String> properties = new ArrayList<>();
		for (StoreSchema store : StoreSchema.values()) {
			if (store == omitted) {
				continue;
			}
			String prefix = "app.datasource." + store.schema() + ".";
			properties.add(prefix + "url="
					+ (store == overridden ? url : VALID_URL_TEMPLATE.formatted(store.schema())));
			properties.add(prefix + "username=" + store.schema() + "_user");
			properties.add(prefix + "password=" + store.schema() + "-local-password");
		}
		return properties.toArray(String[]::new);
	}
}
