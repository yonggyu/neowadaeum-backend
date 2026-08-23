package com.neowadaeum.config;

import com.neowadaeum.config.StoreDataSourceProperties.Store;
import com.zaxxer.hikari.HikariDataSource;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.util.StringUtils;

/**
 * 스토어 하나를 배선하는 팩터리. <b>스토어 수와 무관하게 이 코드가 전부다</b> (ADR-0004).
 *
 * <p>1벌이든 4벌이든 여기를 지나간다. 스토어를 늘리고 줄이는 것은 설정 문제이지 배선 문제가 아니다 —
 * §5.3 이 요구하는 "인스턴스 분리로 승격해도 애플리케이션 코드는 변경이 없어야 한다"가 이 형태로 지켜진다.
 *
 * <p>검증은 <b>빈 생성 시점</b>에 한다. 더 이르게 하면(예: {@code BeanDefinitionRegistryPostProcessor})
 * {@code @DynamicPropertySource} · {@code DynamicPropertyRegistrar} 가 아직 프로퍼티를 넣기 전이라
 * 테스트에서 플레이스홀더가 그대로 보인다.
 */
final class StoreDataSources {

	static final String PREFIX = "app.datasource";

	private StoreDataSources() {
	}

	/**
	 * 스토어 하나의 커넥션 풀. {@code @Primary} 를 붙이지 않는다 — 후보가 하나가 되는 순간
	 * {@code @ConditionalOnSingleCandidate} 자동설정이 되살아나 EntityManagerFactory 를 1벌 만든다.
	 * 그러면 여러 스키마의 엔티티가 한 EMF 에 묶여 JPQL 한 줄로 크로스 스키마 조인이 가능해진다.
	 * <b>스토어가 하나뿐인 지금도 붙이지 않는다</b> — 두 번째가 붙을 때는 이미 엔티티가 그 위에 쌓여 있다.
	 */
	static DataSource create(StoreSchema store, Store settings) {
		validate(store, settings);

		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setPoolName(store.dataSourceBeanName());
		dataSource.setJdbcUrl(settings.url());
		dataSource.setUsername(settings.username());
		dataSource.setPassword(settings.password());
		return dataSource;
	}

	/**
	 * 블록을 뒀다면 세 값이 모두 있어야 한다 (§7.3).
	 *
	 * <p>블록을 아예 두지 않는 것과 절반만 채우는 것은 다르다 — <b>전자는 의도, 후자는 사고다.</b>
	 */
	static void validate(StoreSchema store, Store settings) {
		if (settings == null) {
			throw new IllegalStateException(
					"%s.%s 블록이 없다. 등록 대상이 아니라면 설정에서 빼고, 쓸 것이라면 채운다 (ADR-0004)."
							.formatted(PREFIX, store.schema()));
		}
		require(store, "url", settings.url());
		require(store, "username", settings.username());
		require(store, "password", settings.password());
		requireCurrentSchema(store, settings.url());
	}

	private static void require(StoreSchema store, String key, String value) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalStateException(
					"%s.%s.%s 가 비어 있다. 스토어 블록을 뒀다면 url · username · password 를 모두 채운다 (§7.3)."
							.formatted(PREFIX, store.schema(), key));
		}
	}

	/**
	 * URL 이 자기 스키마를 가리키는지 확인한다.
	 *
	 * <p>{@code currentSchema} 가 빠지면 PostgreSQL 기본 {@code search_path} 로 떨어져 마이그레이션과 모든
	 * 테이블이 조용히 다른 스키마에 만들어진다. 실패가 드러나는 시점이 한참 뒤라 추적 비용이 크다.
	 *
	 * <p><b>부분 문자열 매칭을 쓰지 않는다.</b> {@code contains("currentSchema=play")} 는
	 * {@code currentSchema=playground} 도 통과시킨다. 쉼표도 끝으로 인정하지 않는다 —
	 * {@code currentSchema=play,public} 은 {@code search_path} 를 넓히는 것이라 §5.3 이 막으려는 상태다.
	 *
	 * <p>예외 메시지에 URL 을 넣지 않는다. 이 레포는 공개이고 로그도 공개될 수 있다(S-11, S-3).
	 */
	private static void requireCurrentSchema(StoreSchema store, String url) {
		String expected = "currentSchema=" + store.schema();
		if (!Pattern.compile(Pattern.quote(expected) + "(?:[&;]|$)").matcher(url).find()) {
			throw new IllegalStateException(
					"%s.%s.url 이 %s 로 끝나는 값을 갖지 않는다. §5.3 의 스키마 분리가 성립하지 않는다."
							.formatted(PREFIX, store.schema(), expected));
		}
	}
}
