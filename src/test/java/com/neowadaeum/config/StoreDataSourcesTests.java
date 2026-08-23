package com.neowadaeum.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.neowadaeum.config.StoreDataSourceProperties.Store;
import org.junit.jupiter.api.Test;

/**
 * §5.3 / §7.3 — 잘못된 {@code app.datasource.*} 설정에서 <b>빈 생성이 실패하는지</b>, 그리고 실패가
 * <b>무엇이 잘못됐는지 말하는지</b> 확인한다.
 *
 * <p><b>컨텍스트도 DB 도 필요 없다.</b> {@link StoreDataSources} 의 검증은 순수 함수다.
 * 실제 스키마 분리와 마이그레이션은 {@link StoreSeparationTests} 가 본다(§10, ADR-0001).
 *
 * <p>등록 여부 자체(ADR-0004 — 설정된 스토어만 뜬다)는 {@link AutoConfigurationExclusionTests} 가
 * 실제 컨텍스트에서 확인한다. 조건부 등록은 Spring 이 하는 일이라 여기서 흉내 내지 않는다.
 */
class StoreDataSourcesTests {

	private static final String VALID_URL_TEMPLATE = "jdbc:postgresql://localhost:5432/neowadaeum?currentSchema=%s";

	/** 정상 설정은 통과한다. */
	@Test
	void S5_3_valid_settings_are_accepted() {
		assertThatCode(() -> StoreDataSources.validate(StoreSchema.PLAY, store(StoreSchema.PLAY)))
				.doesNotThrowAnyException();
	}

	/** ADR-0004 — 블록이 null 이면 그 사실을 말한다. 조건부 등록이 어긋났을 때의 안전망이다. */
	@Test
	void ADR0004_missing_block_names_the_store() {
		assertThatIllegalStateException()
				.isThrownBy(() -> StoreDataSources.validate(StoreSchema.CATALOG, null))
				.withMessageContaining("app.datasource.catalog");
	}

	/**
	 * §7.3 — 블록을 뒀는데 값이 비면 실패하고, <b>어느 스토어의 어느 키</b>인지가 드러난다.
	 *
	 * <p>블록을 아예 두지 않는 것과 절반만 채우는 것은 다르다 — 전자는 의도, 후자는 사고다.
	 */
	@Test
	void S7_3_blank_value_names_the_key() {
		Store settings = new Store(VALID_URL_TEMPLATE.formatted("promptlog"), "promptlog_user", "  ");

		assertThatIllegalStateException()
				.isThrownBy(() -> StoreDataSources.validate(StoreSchema.PROMPTLOG, settings))
				.withMessageContaining("app.datasource.promptlog.password");
	}

	/** §5.3 — {@code currentSchema} 가 없으면 실패한다. 빠지면 모든 테이블이 조용히 다른 스키마에 생긴다. */
	@Test
	void S5_3_url_without_current_schema_fails() {
		assertThatIllegalStateException()
				.isThrownBy(() -> StoreDataSources.validate(StoreSchema.CATALOG,
						withUrl(StoreSchema.CATALOG, "jdbc:postgresql://localhost:5432/neowadaeum")))
				.withMessageContaining("currentSchema=catalog");
	}

	/**
	 * §5.3 회귀 방지 — 접두어만 같은 스키마를 통과시키지 않는다. 그리고 S-11 — 실패에 URL 을 흘리지 않는다.
	 *
	 * <p>{@code contains("currentSchema=play")} 로 검사하면 {@code currentSchema=playground} 가 통과한다.
	 *
	 * <p><b>단언이 두 겹인 이유.</b> "있어야 할 것"만 보면, 실패 URL 자체가 {@code currentSchema=play} 를
	 * 담고 있으므로 <b>URL 이 통째로 메시지에 새어 나와도 통과한다.</b> 이 레포는 공개이고 CI 로그도
	 * 공개된다(S-11, S-3).
	 */
	@Test
	void S5_3_prefix_matching_schema_fails_without_leaking_the_url() {
		String wrongUrl = "jdbc:postgresql://localhost:5432/neowadaeum?currentSchema=playground";

		assertThatIllegalStateException()
				.isThrownBy(() -> StoreDataSources.validate(StoreSchema.PLAY, withUrl(StoreSchema.PLAY, wrongUrl)))
				.withMessageContaining("app.datasource.play.url")
				.withMessageContaining("currentSchema=play")
				.withMessageNotContaining(wrongUrl)
				.withMessageNotContaining("localhost:5432")
				.withMessageNotContaining("playground");
	}

	/**
	 * §5.3 회귀 방지 — {@code search_path} 를 넓히는 목록 표기를 통과시키지 않는다.
	 *
	 * <p>{@code currentSchema=identity,public} 은 드라이버가 받아들이지만 §5.3 이 막으려는 상태 그 자체다.
	 */
	@Test
	void S5_3_schema_list_fails() {
		assertThatIllegalStateException()
				.isThrownBy(() -> StoreDataSources.validate(StoreSchema.IDENTITY, withUrl(StoreSchema.IDENTITY,
						"jdbc:postgresql://localhost:5432/neowadaeum?currentSchema=identity,public")))
				.withMessageContaining("currentSchema=identity");
	}

	/** 뒤에 다른 파라미터가 붙는 정상 형태는 통과해야 한다. 경계 검사가 과하지 않은지 본다. */
	@Test
	void S5_3_trailing_parameter_is_accepted() {
		assertThatCode(() -> StoreDataSources.validate(StoreSchema.PLAY, withUrl(StoreSchema.PLAY,
				"jdbc:postgresql://localhost:5432/neowadaeum?currentSchema=play&ApplicationName=neowadaeum")))
				.doesNotThrowAnyException();
	}

	private static Store store(StoreSchema schema) {
		return new Store(VALID_URL_TEMPLATE.formatted(schema.schema()), schema.schema() + "_user",
				schema.schema() + "-local-password");
	}

	private static Store withUrl(StoreSchema schema, String url) {
		return new Store(url, schema.schema() + "_user", schema.schema() + "-local-password");
	}
}
