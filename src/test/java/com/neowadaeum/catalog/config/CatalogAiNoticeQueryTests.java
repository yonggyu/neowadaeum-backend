package com.neowadaeum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.ServiceConfigQuery;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * B-14 — 설정값의 모양을 아는 유일한 곳 (R11.1).
 *
 * <p>읽는 쪽마다 그 모양을 알면 그중 하나가 늦게 바뀐다. 여기서 깨지면 <b>고지 하나 때문에
 * 화면 전체가 죽지 않고</b> 비어 있는 것으로 처리된다 — 대신 그 사실이 로그에 남는다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class CatalogAiNoticeQueryTests {

	private final ServiceConfigQuery configs = mock(ServiceConfigQuery.class);

	private final CatalogAiNoticeQuery query = new CatalogAiNoticeQuery(this.configs);

	/** R11.1 — 판본과 문구가 설정에서 온다. */
	@Test
	void R11_1_a_configured_notice_is_parsed() {
		givenConfig("{\"version\":\"2026-07-21\",\"text\":\"이 이야기는 AI가 생성합니다.\"}");

		assertThat(this.query.current())
				.contains(new AiNotice("2026-07-21", "이 이야기는 AI가 생성합니다."));
	}

	/** 설정이 없으면 비어 있다 — 기본 문구를 만들지 않는다 (R11.1). */
	@Test
	void R11_1_an_unset_notice_is_empty() {
		given(this.configs.find(CatalogAiNoticeQuery.NOTICE_KEY)).willReturn(Optional.empty());

		assertThat(this.query.current()).isEmpty();
	}

	/** 모양이 어긋나도 예외를 올리지 않는다 — 고지 하나 때문에 화면이 죽지 않는다. */
	@Test
	void R11_1_a_malformed_value_is_empty_not_an_exception() {
		givenConfig("{\"version\":\"2026-07-21\"}");

		assertThat(this.query.current()).isEmpty();
	}

	/** JSON 이 아닌 값도 같다. */
	@Test
	void R11_1_a_non_json_value_is_empty() {
		givenConfig("이건 JSON 이 아니다");

		assertThat(this.query.current()).isEmpty();
	}

	private void givenConfig(String raw) {
		given(this.configs.find(CatalogAiNoticeQuery.NOTICE_KEY)).willReturn(Optional.of(raw));
	}
}
