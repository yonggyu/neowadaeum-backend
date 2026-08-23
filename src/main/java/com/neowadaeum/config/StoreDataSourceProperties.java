package com.neowadaeum.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * §5.3 4-스토어 접속 정보. {@code app.datasource.*} 를 그대로 반영한다.
 *
 * <p>키 이름은 {@code .env.example}(§7.4) 및 {@code application.yml.template}(§7.3)과 1:1로 묶여 있다.
 * <strong>이름을 바꾸면 각자의 로컬 {@code .env} 가 조용히 깨진다.</strong> 바꿔야 한다면 세 파일을 함께 고친다.
 *
 * <p>{@link NotBlank} 와 {@link NotNull} 을 거는 이유는 §7.3이다. 값이 비면 기본값으로 조용히 뜨는 대신
 * 부팅을 실패시킨다. 잘못된 DB 에 붙어 도는 것보다 안 뜨는 편이 낫다.
 *
 * <p>{@code @NotNull} 이 따로 필요한 이유 — {@code app.datasource.play} 블록이 통째로 빠지면 생성자 바인딩이
 * {@code null} 을 넣고, 검증 없이는 DataSource 를 만들 때 {@code NullPointerException} 이 난다. NPE 는
 * <b>무엇이 비었는지 말하지 않는다.</b> 목적은 fail-fast 자체가 아니라 "무엇이 비었는지 말하는" fail-fast 다.
 */
@Validated
@ConfigurationProperties("app.datasource")
public record StoreDataSourceProperties(
		@NotNull(message = MISSING_STORE) @Valid Store identity,
		@NotNull(message = MISSING_STORE) @Valid Store catalog,
		@NotNull(message = MISSING_STORE) @Valid Store play,
		@NotNull(message = MISSING_STORE) @Valid Store promptlog) {

	/** 어느 스토어인지는 바인딩 오류의 필드명이 알려준다. 이 문구는 왜 실패했는지를 알려준다. */
	static final String MISSING_STORE =
			"스토어 블록이 없다. 네 스토어(identity/catalog/play/promptlog)를 모두 정의한다 (§5.3)";

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
