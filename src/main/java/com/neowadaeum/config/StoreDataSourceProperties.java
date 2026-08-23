package com.neowadaeum.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * §5.3 4-스토어 접속 정보. {@code app.datasource.*} 를 그대로 반영한다.
 *
 * <p>키 이름은 {@code .env.example}(§7.4) 및 {@code application.yml.template}(§7.3)과 1:1로 묶여 있다.
 * <strong>이름을 바꾸면 각자의 로컬 {@code .env} 가 조용히 깨진다.</strong> 바꿔야 한다면 세 파일을 함께 고친다.
 *
 * <p>{@link NotBlank} 를 거는 이유는 §7.3이다. 값이 비면 기본값으로 조용히 뜨는 대신 부팅을 실패시킨다.
 * 잘못된 DB 에 붙어 도는 것보다 안 뜨는 편이 낫다.
 */
@Validated
@ConfigurationProperties("app.datasource")
public record StoreDataSourceProperties(
		@Valid Store identity,
		@Valid Store catalog,
		@Valid Store play,
		@Valid Store promptlog) {

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
