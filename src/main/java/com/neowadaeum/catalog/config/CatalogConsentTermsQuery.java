package com.neowadaeum.catalog.config;

import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.spi.ConsentTerm;
import com.neowadaeum.common.spi.ConsentTermsQuery;
import com.neowadaeum.common.spi.ServiceConfigQuery;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설정에 든 약관 판본을 값으로 바꾼다 (R10.2, 이슈 #261).
 *
 * <p><b>설정값의 모양을 아는 유일한 곳</b>이다 — {@link CatalogAiNoticeQuery} 와 같은 형태다.
 *
 * <pre>{@code
 * service_config['consent.terms'] = {
 *   "tos":     {"version": "...", "documentUrl": "..."},
 *   "privacy": {"version": "...", "documentUrl": "..."}
 * }
 * }</pre>
 *
 * <p><b>{@code ai_notice} 는 이 표에서 읽지 않는다.</b> 그 동의의 판본은 <b>사용자가 실제로 본
 * 고지의 판본</b>이어야 한다 (R11.3, §13-8) — 두 곳에 따로 적으면 고지를 갱신한 날 둘이
 * 어긋나고, 어긋난 쪽이 동의 이력에 남는다. 그래서 {@link AiNoticeQuery} 를 그대로 쓴다.
 *
 * <p><b>모양이 어긋나면 비어 있다.</b> 예외를 올리면 설정 하나 때문에 화면 전체가 죽는다.
 * 대신 <b>그 사실을 로그에 남긴다</b>. <b>값 자체는 로그에 싣지 않는다</b>: 키와 이유만 남긴다.
 */
@Component
public class CatalogConsentTermsQuery implements ConsentTermsQuery {

	/** {@code service_config} 의 키. 값의 모양은 이 클래스가 정한다. */
	static final String TERMS_KEY = "consent.terms";

	/** 고지 동의의 판본은 고지 자체가 갖는다 (R11.3). */
	static final String AI_NOTICE = "ai_notice";

	private static final Logger log = LoggerFactory.getLogger(CatalogConsentTermsQuery.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ServiceConfigQuery configs;

	private final AiNoticeQuery notices;

	public CatalogConsentTermsQuery(ServiceConfigQuery configs, AiNoticeQuery notices) {
		this.configs = configs;
		this.notices = notices;
	}

	@Override
	public Optional<ConsentTerm> find(String consentType) {
		if (AI_NOTICE.equals(consentType)) {
			// 문구는 랜딩이 이미 내보낸다 (§13.10). 여기서 필요한 것은 판본뿐이다.
			return this.notices.current().map(notice -> new ConsentTerm(notice.version(), null));
		}
		return this.configs.find(TERMS_KEY).flatMap(raw -> parse(raw, consentType));
	}

	private static Optional<ConsentTerm> parse(String raw, String consentType) {
		try {
			JsonNode term = JSON.readTree(raw).path(consentType);
			String version = term.path("version").asString();
			if (version.isBlank()) {
				// 설정은 있는데 이 종류가 없다. 어긋난 것이 아니라 <b>아직 넣지 않은 것</b>이다.
				return Optional.empty();
			}
			String documentUrl = term.path("documentUrl").asString();
			return Optional.of(new ConsentTerm(version, documentUrl.isBlank() ? null : documentUrl));
		}
		catch (RuntimeException ex) {
			log.warn("service.config.malformed key={} reason={}", TERMS_KEY, ex.getClass().getSimpleName());
			return Optional.empty();
		}
	}
}
