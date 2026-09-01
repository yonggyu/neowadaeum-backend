package com.neowadaeum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.neowadaeum.ContainerTestBase;
import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import com.neowadaeum.common.spi.ServiceConfigQuery;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-14(1/2) — 운영 설정이 <b>catalog 에서 읽히고</b>, 읽는 쪽이 소유 모듈을 모른다 (R11.1, §13-4).
 *
 * <p>이 표는 promptlog 에 있었다. 옮긴 이유는 문서 일치만이 아니다 — promptlog 의 엔티티를
 * 소유하는 모듈은 {@code ai} 이고, 고지 문구를 읽어야 하는 {@code identity} · {@code play} 는
 * <b>{@code ai} 를 참조할 수 없다</b> (ADR-0006). 지금 위치로는 읽을 방법이 없었다.
 */
class ServiceConfigQueryTests extends ContainerTestBase {

	private static final Instant NOW = Instant.parse("2026-08-27T04:05:06Z");

	@Autowired
	private ServiceConfigRepository configs;

	@Autowired
	private ServiceConfigQuery query;

	@AfterEach
	void clear() {
		this.configs.deleteAll();
	}

	/** R11.1 — 값이 코드가 아니라 설정에서 온다. */
	@Test
	void R11_1_a_stored_value_is_readable_through_the_spi() {
		this.configs.save(ServiceConfig.of("ai.notice.text", "\"이 이야기는 AI가 생성합니다.\"", NOW));

		assertThat(this.query.find("ai.notice.text")).contains("\"이 이야기는 AI가 생성합니다.\"");
	}

	/**
	 * <b>없는 값은 비어 있다 — 폴백을 만들지 않는다.</b>
	 *
	 * <p>"설정이 없으면 하드코딩된 문구" 같은 경로를 두면 R11.1 이 무너진다. 문구가 코드에
	 * 없다는 것이 요구사항이다.
	 */
	@Test
	void R11_1_a_missing_key_is_empty_not_a_default() {
		assertThat(this.query.find("ai.notice.text")).isEmpty();
	}

	/** <b>배포 없이 갱신된다</b> (R11.1). 그것이 이 표의 존재 이유다. */
	@Test
	void R11_1_a_value_can_be_updated_without_a_deployment() {
		ServiceConfig config = this.configs.save(ServiceConfig.of("ai.notice.text", "\"이전 문구\"", NOW));

		config.update("\"새 문구\"", NOW.plusSeconds(60));
		this.configs.saveAndFlush(config);

		assertThat(this.query.find("ai.notice.text")).contains("\"새 문구\"");
		assertThat(this.configs.findById("ai.notice.text").orElseThrow().getUpdatedAt())
				.isEqualTo(NOW.plusSeconds(60));
	}

	/**
	 * <b>구현이 하나뿐이다.</b>
	 *
	 * <p>둘이 되면 어느 쪽이 답하는지가 빈 등록 순서에 달린 문제가 된다 — 세이프티 SPI 와 같은
	 * 원칙이다 (ADR-0002).
	 */
	@Test
	void S13_4_the_query_is_backed_by_catalog_alone() {
		assertThat(this.query).isInstanceOf(CatalogServiceConfigQuery.class);
	}
}
