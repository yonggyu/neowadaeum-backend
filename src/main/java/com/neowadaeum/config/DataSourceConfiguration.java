package com.neowadaeum.config;

import com.neowadaeum.config.StoreDataSourceProperties.Store;
import com.zaxxer.hikari.HikariDataSource;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * §5.3 스토어 물리 분리 — DataSource 4개.
 *
 * <p>스토어마다 별도 스키마 · 별도 계정 · 별도 커넥션 풀이다. 로컬에서는 컨테이너 1개 안의 스키마 4개로
 * 시작하고(§2.5), 트래픽·규제 요구에 따라 인스턴스 분리로 승격한다. <b>승격 시 애플리케이션 코드는 변경이
 * 없어야 한다</b> — 그래서 스키마 간 FK 와 JOIN 을 금지한다.
 *
 * <p>이 분리가 I-3 의 구조적 전제다. 비-Identity 스키마는 회원 식별정보를 담지 않고 {@code player_ref}
 * 만 담는다(§5.3). 스키마와 계정이 갈라져 있어야 "저장할 수 있는데 안 저장한다"가 아니라 "저장할 수 없다"가 된다.
 *
 * <p><b>{@code @Primary} 를 붙이지 않는다.</b> 후보가 하나가 되는 순간 {@code @ConditionalOnSingleCandidate}
 * 자동설정들이 되살아나 EntityManagerFactory 를 1벌 만든다. 그러면 네 스키마의 엔티티가 한 EMF 에 묶여
 * JPQL 한 줄로 크로스 스키마 조인이 가능해지고, §5.3 의 분리가 그 시점에 무효가 된다.
 * 스토어별 EMF / TransactionManager 4벌은 B-05-1 에서 명시 구성한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StoreDataSourceProperties.class)
public class DataSourceConfiguration {

	@Bean
	public DataSource identityDataSource(StoreDataSourceProperties properties) {
		return dataSource(StoreSchema.IDENTITY, properties.identity());
	}

	@Bean
	public DataSource catalogDataSource(StoreDataSourceProperties properties) {
		return dataSource(StoreSchema.CATALOG, properties.catalog());
	}

	@Bean
	public DataSource playDataSource(StoreDataSourceProperties properties) {
		return dataSource(StoreSchema.PLAY, properties.play());
	}

	@Bean
	public DataSource promptLogDataSource(StoreDataSourceProperties properties) {
		return dataSource(StoreSchema.PROMPTLOG, properties.promptlog());
	}

	private static DataSource dataSource(StoreSchema store, Store settings) {
		requireCurrentSchema(store, settings.url());

		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setPoolName(store.dataSourceBeanName());
		dataSource.setJdbcUrl(settings.url());
		dataSource.setUsername(settings.username());
		dataSource.setPassword(settings.password());
		return dataSource;
	}

	/**
	 * URL 이 자기 스키마를 가리키는지 부팅 시점에 확인한다.
	 *
	 * <p>{@code currentSchema} 가 빠지면 PostgreSQL 기본 {@code search_path} 로 떨어져 마이그레이션과 모든
	 * 테이블이 조용히 다른 스키마에 만들어진다. 실패가 드러나는 시점이 한참 뒤라 추적 비용이 크다.
	 * 조용히 잘못된 값으로 뜨는 것보다 안 뜨는 게 낫다(§7.3).
	 *
	 * <p><b>부분 문자열 매칭을 쓰지 않는다.</b> {@code contains("currentSchema=play")} 는
	 * {@code currentSchema=playground} 도 통과시킨다. 이 검사의 목적이 조용한 오배치를 막는 것이므로
	 * 값의 끝을 함께 본다 — 뒤는 {@code &} · {@code ;} 또는 문자열 끝이어야 한다.
	 *
	 * <p>쉼표도 끝으로 인정하지 않는다. PostgreSQL 드라이버는 {@code currentSchema=play,public} 처럼
	 * 목록을 받는데, 그것은 {@code search_path} 를 넓히는 것이라 §5.3 이 막으려는 바로 그 상태다.
	 *
	 * <p>예외 메시지에 URL 을 넣지 않는다. 이 레포는 공개이고 로그도 공개될 수 있다(S-11, S-3).
	 */
	private static void requireCurrentSchema(StoreSchema store, String url) {
		String expected = "currentSchema=" + store.schema();
		Pattern exact = Pattern.compile(Pattern.quote(expected) + "(?:[&;]|$)");
		if (!exact.matcher(url).find()) {
			throw new IllegalStateException(
					"app.datasource.%s.url 이 %s 로 끝나는 값을 갖지 않는다. §5.3 의 스키마 분리가 성립하지 않는다."
							.formatted(store.schema(), expected));
		}
	}
}
