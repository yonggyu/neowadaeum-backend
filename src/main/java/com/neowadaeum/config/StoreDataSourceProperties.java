package com.neowadaeum.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.EnumMap;
import java.util.Collections;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * §5.3 4-스토어 접속 정보. {@code app.datasource.*} 를 그대로 반영한다.
 *
 * <p>키 이름은 {@code .env.example}(§7.4) 및 {@code application.yml.template}(§7.3)과 1:1로 묶여 있다.
 * <strong>이름을 바꾸면 각자의 로컬 {@code .env} 가 조용히 깨진다.</strong> 바꿔야 한다면 세 파일을 함께 고친다.
 *
 * <p><b>블록이 있는 스토어만 등록된다</b>(ADR-0004). 네 스토어를 전부 채울 필요가 없다 — MVP 수직 슬라이스는
 * {@code play} 하나로 돌고, 스토어가 늘어나는 것은 <b>설정 추가</b>지 코드 변경이 아니다. §5.3 이 요구하는
 * "승격 시 애플리케이션 코드는 변경이 없어야 한다"가 이 형태로 지켜진다.
 *
 * <p>{@link NotBlank} 를 거는 이유는 §7.3이다. <b>블록을 뒀다면 세 값이 모두 있어야 한다.</b> 절반만 채우면
 * 기본값으로 조용히 뜨는 대신 부팅을 실패시킨다. 잘못된 DB 에 붙어 도는 것보다 안 뜨는 편이 낫다.
 * 블록을 아예 두지 않는 것과 절반만 채우는 것은 다르다 — 전자는 의도, 후자는 사고다.
 */
@Validated
@ConfigurationProperties("app.datasource")
public record StoreDataSourceProperties(
		@Valid Store identity,
		@Valid Store catalog,
		@Valid Store play,
		@Valid Store promptlog) {

	/**
	 * 설정된 스토어만 담아 돌려준다. 등록 대상의 단일 출처다.
	 *
	 * <p>순서는 {@link StoreSchema} 선언 순서로 고정한다. 빈 이름·로그·테스트가 순서에 기대지는 않지만,
	 * 기동 로그를 사람이 읽을 때 매번 달라지면 비교가 안 된다.
	 */
	public Map<StoreSchema, Store> configured() {
		Map<StoreSchema, Store> result = new EnumMap<>(StoreSchema.class);
		putIfPresent(result, StoreSchema.IDENTITY, this.identity);
		putIfPresent(result, StoreSchema.CATALOG, this.catalog);
		putIfPresent(result, StoreSchema.PLAY, this.play);
		putIfPresent(result, StoreSchema.PROMPTLOG, this.promptlog);
		return Collections.unmodifiableMap(result);
	}

	private static void putIfPresent(Map<StoreSchema, Store> target, StoreSchema store, Store settings) {
		if (settings != null) {
			target.put(store, settings);
		}
	}

	/**
	 * 스토어 하나의 접속 정보.
	 *
	 * <p>URL 은 {@code ?currentSchema=<스키마>} 를 포함한다. 각 계정은 자기 스키마에만 권한을 가지므로(§5.3),
	 * 다른 스키마를 참조하는 쿼리는 로컬에서 곧바로 권한 오류로 터진다. 운영에서 발견하는 것보다 낫다.
	 */
	public record Store(
			@NotBlank String url,
			@NotBlank String username,
			@NotBlank String password) {
	}
}
