package com.neowadaeum.catalog.config;

import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code dev} 에서 필수 운영 데이터를 <b>자리표시</b>로 채운다 (§13-89, 이슈 #426).
 *
 * <p><b>왜 필요한가.</b> {@link RequiredOperationalDataCheck} 가 {@code prod} 를 세우는 대신
 * 로컬까지 세우면 개발자는 매번 손으로 행을 넣어야 하고, <b>그 값을 어디서 얻는지가 다시 문제가
 * 된다.</b> 확정 문구는 저장소 밖에서 오므로(S-11) 로컬에 줄 값이 없다.
 *
 * <p><b>{@code @Profile("dev & !prod")} 다 — {@code "!prod"} 가 아니다</b> (#47). 그 표현식은
 * <b>프로파일이 하나도 활성화되지 않은 상태에서도 참</b>이라, 프로파일을 빠뜨리고 뜬 인스턴스에
 * 자리표시가 들어간다. 차단이 "이름을 빠뜨리지 않는 것"에 의존하면 언젠가 빠뜨린다. 표현식이
 * dev 콘솔(B-47)·결정론 Provider(S-3)·계약 문서(B-06)와 같은 것도 의도다 — {@code README.md} 가
 * <i>"표현식이 같으므로 {@code dev} 하나만 켜면 전부 해결된다"</i> 고 적어 두었다.
 *
 * <p><b>마이그레이션이 아닌 이유.</b> Flyway 는 프로파일을 모른다 — {@code StoreMigration} 은
 * 스토어마다 위치 하나만 읽고 그 값은 프로파일에 따라 갈리지 않는다. 마이그레이션으로 넣으면
 * <b>자리표시가 운영 DB 에도 들어간다.</b> 대신 받는 대가는 <b>마이그레이션 이력에 남지 않는다</b>는
 * 것이다 — "언제 들어왔는가"는 이 행으로 추적되지 않는다.
 *
 * <p><b>값이 스스로 가짜라고 말한다.</b> 판본은 §13-51 이 정한 {@code v<major>.<minor>} 를 <b>쓰지
 * 않는다</b> — 나중에 형식 검증을 붙일 때 자리표시가 걸리는 것이 옳은 동작이다. 문구는 표지로
 * <b>시작한다</b>: 프로파일 경계가 못 잡아도 사람 눈이 잡는다.
 *
 * <p><b>이미 있는 값은 건드리지 않는다.</b> 로컬에 진짜 문구를 넣어 두고 화면을 보는 경우가 있고,
 * 뜰 때마다 덮어쓰면 그 작업이 조용히 지워진다.
 *
 * <p><b>여기 사는 이유.</b> {@code service_config} 는 catalog 스키마의 표이고, 그것을 쓸 수 있는
 * Repository 를 가진 모듈은 catalog 하나다 (§5.3).
 *
 * <p>이 경계는 애노테이션이 붙어 있다는 확인이 아니라 <b>동작</b>으로 검증한다 (#34, ADR-0004) —
 * {@code DevServiceConfigSeedTests}.
 */
@Component
@Profile("dev & !prod")
@Order(DevServiceConfigSeed.ORDER)
public class DevServiceConfigSeed implements ApplicationRunner {

	/** 검사보다 <b>먼저</b> 돈다. */
	static final int ORDER = 0;

	/**
	 * 자리표시 판본. <b>§13-51 의 {@code v<major>.<minor>} 와 형식으로 갈린다.</b>
	 *
	 * <p>동의 이력({@code consent_log})은 append-only 이므로 이 판본으로 남은 동의는 지울 수
	 * 없다. {@code dev} 에서만 생기지만 그 성질은 그대로다 — 그래서 값이 <b>스스로 가짜라고
	 * 말해야 한다.</b>
	 */
	static final String PLACEHOLDER_VERSION = "dev-placeholder";

	/** 사람 눈이 잡는 표지. 문구는 이것으로 <b>시작한다</b>. */
	static final String PLACEHOLDER_MARK = "[개발용 자리표시 · 실제 약관 아님]";

	/**
	 * <b>운영 문구가 아니다.</b> 실제 고지 문구와 약관 주소는 이 저장소에 넣지 않는다 (S-11).
	 * {@code documentUrl} 을 비워 두는 것도 같은 이유이며, 화면은 링크 없는 항목을 그린다 (§13-51).
	 */
	private static final String NOTICE_VALUE = """
			{"version":"%s","text":"%s 운영 문구는 배포 절차의 0번 단계에서 넣는다."}"""
			.formatted(PLACEHOLDER_VERSION, PLACEHOLDER_MARK);

	private static final String TERMS_VALUE = """
			{"tos":{"version":"%1$s"},"privacy":{"version":"%1$s"}}""".formatted(PLACEHOLDER_VERSION);

	private static final Logger log = LoggerFactory.getLogger(DevServiceConfigSeed.class);

	private final ServiceConfigRepository configs;

	private final Clock clock;

	public DevServiceConfigSeed(ServiceConfigRepository configs, Clock clock) {
		this.configs = configs;
		this.clock = clock;
	}

	/**
	 * <b>매니저를 명시한다</b> — 후보가 넷이므로 이름 없는 {@code @Transactional} 은 부팅에서
	 * 실패한다 ({@code CatalogServiceConfigQuery} 와 같은 이유).
	 */
	@Override
	@Transactional(transactionManager = "catalogTransactionManager")
	public void run(ApplicationArguments args) {
		seedIfAbsent(CatalogAiNoticeQuery.NOTICE_KEY, NOTICE_VALUE);
		seedIfAbsent(CatalogConsentTermsQuery.TERMS_KEY, TERMS_VALUE);
	}

	private void seedIfAbsent(String key, String value) {
		if (this.configs.existsById(key)) {
			return;
		}
		this.configs.save(ServiceConfig.of(key, value, Instant.now(this.clock)));
		// 자리표시가 들어갔다는 사실 자체가 신호다. 값은 싣지 않는다 (S-3).
		log.warn("service.config.dev.seeded key={} — 개발용 자리표시다. 운영 값이 아니다", key);
	}
}
