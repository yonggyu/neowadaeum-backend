package com.neowadaeum.catalog.config;

import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.spi.ServiceConfigQuery;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설정에 든 고지 문구를 값으로 바꾼다 (R11.1, B-14).
 *
 * <p><b>설정값의 모양을 아는 유일한 곳</b>이다. 읽는 쪽마다 알면 그중 하나가 늦게 바뀐다.
 *
 * <pre>{@code
 * service_config['ai.notice'] = {"version": "2026-07-21", "text": "..."}
 * }</pre>
 *
 * <p><b>모양이 어긋나면 비어 있다.</b> 예외를 올리면 고지 하나 때문에 화면 전체가 죽는다.
 * 대신 <b>그 사실을 로그에 남긴다</b> — 조용히 넘어가면 고지가 없는 상태가 정상으로 보인다.
 * <b>문구 자체는 로그에 싣지 않는다</b>: 값이 아니라 키와 이유만 남긴다.
 */
@Component
public class CatalogAiNoticeQuery implements AiNoticeQuery {

	/** {@code service_config} 의 키. 값의 모양은 이 클래스가 정한다. */
	static final String NOTICE_KEY = "ai.notice";

	private static final Logger log = LoggerFactory.getLogger(CatalogAiNoticeQuery.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ServiceConfigQuery configs;

	public CatalogAiNoticeQuery(ServiceConfigQuery configs) {
		this.configs = configs;
	}

	@Override
	public Optional<AiNotice> current() {
		return this.configs.find(NOTICE_KEY).flatMap(CatalogAiNoticeQuery::parse);
	}

	private static Optional<AiNotice> parse(String raw) {
		try {
			JsonNode node = JSON.readTree(raw);
			return Optional.of(new AiNotice(node.path("version").asString(), node.path("text").asString()));
		}
		catch (RuntimeException ex) {
			log.warn("service.config.malformed key={} reason={}", NOTICE_KEY, ex.getClass().getSimpleName());
			return Optional.empty();
		}
	}
}
