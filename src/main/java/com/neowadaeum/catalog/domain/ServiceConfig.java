package com.neowadaeum.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 운영 중 바뀌는 설정 한 건 (R11.1, §13-4).
 *
 * <p><b>AI 고지 문구가 여기 산다.</b> §11 은 그것을 하드코딩하지 말고 이 표에서 관리해
 * <b>배포 없이 갱신</b>할 수 있게 하라고 요구한다 (R11.1). 법이 요구하는 문구는 고시와 함께
 * 바뀌며, 그때마다 배포가 필요하면 늦는다.
 *
 * <p><b>값이 {@code jsonb} 인 것은 B-11 이 정한 형태다.</b> 문구 하나라도 문자열이 아니라 JSON
 * 으로 두면 나중에 {@code {"text": ..., "version": ...}} 처럼 늘릴 때 표를 바꾸지 않아도 된다.
 *
 * <p>누가 언제 바꿨는지는 여기가 아니라 {@code admin_audit_log} 다 (R14.5) — 감사 기록과
 * 현재 값은 다른 것이다.
 */
@Entity
@Table(name = "service_config")
public class ServiceConfig {

	@Id
	@Column(name = "config_key", nullable = false, updatable = false)
	private String configKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "config_value", nullable = false)
	private String configValue;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ServiceConfig() {
	}

	public static ServiceConfig of(String configKey, String configValue, Instant now) {
		if (configKey == null || configKey.isBlank() || configValue == null || configValue.isBlank()) {
			throw new IllegalArgumentException("configKey, configValue are required");
		}
		ServiceConfig config = new ServiceConfig();
		config.configKey = configKey;
		config.configValue = configValue;
		config.updatedAt = now;
		return config;
	}

	/** 값을 바꾼다. 배포 없이 갱신하는 경로가 이것이다 (R11.1, B-41). */
	public void update(String configValue, Instant now) {
		if (configValue == null || configValue.isBlank()) {
			throw new IllegalArgumentException("configValue is required");
		}
		this.configValue = configValue;
		this.updatedAt = now;
	}

	public String getConfigKey() {
		return this.configKey;
	}

	public String getConfigValue() {
		return this.configValue;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}
}
