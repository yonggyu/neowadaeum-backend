package com.neowadaeum.catalog.config;

import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.spi.ConsentTermsQuery;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 필수 운영 데이터를 <b>기동 때</b> 확인한다 (§7.3 · §13-89, 이슈 #426).
 *
 * <p><b>문제는 500 이 아니라 실패의 시점이었다.</b> 고지 문구와 약관 판본이 없어도 서버는 정상
 * 부팅했고, 그 사실은 <b>사용자가 첫 화면을 열 때</b>만 드러났다. §7.3 은 *"값이 없으면 부팅을
 * 실패하게 한다"* 를 설정값에 걸어 두었는데({@code CorsProperties} 가 그 예다) 이 둘은 성질이
 * 같으면서 <b>YAML 이 아니라 행이라는 이유로</b> 그 규칙 밖에 있었다. 그 선을 지운다.
 *
 * <p><b>{@code prod} 에서만 세운다.</b> 로컬에서 부팅을 세우면 개발자는 매번 손으로 행을 넣어야
 * 하고, 그 값을 어디서 얻는지가 다시 문제가 된다 — {@code dev} 는 {@link DevServiceConfigSeed}
 * 가 자리표시로 채운다. 그 밖의 프로파일에서는 <b>기록만 남긴다.</b>
 *
 * <p><b>readiness 가 아니라 부팅이다.</b> 뜬 채로 트래픽만 안 받는 인스턴스는 배포 파이프라인에서
 * <b>타임아웃</b>으로 보이지 원인으로 보이지 않는다. 여기서 예외를 올리면 컨텍스트가 닫히고
 * 프로세스가 끝나므로 그 인스턴스는 트래픽을 받지 못하며, <b>무엇이 없는지가 기동 로그에 남는다.</b>
 *
 * <p><b>{@link ApplicationRunner} 인 이유.</b> 컨텍스트 새로고침이 끝난 뒤 도는 자리라 마이그레이션과
 * DataSource 가 이미 준비돼 있다. {@code @PostConstruct} 는 그 순서를 보장하지 않는다.
 *
 * <p><b>요청 시점의 응답은 그대로다.</b> 값이 없을 때 {@code 500 INTERNAL_ERROR} + {@code log.error}
 * 는 §13-27 · §13-51 이 정한 것이고 이 클래스는 그것을 건드리지 않는다 — <b>더 이른 자리를 하나
 * 더할 뿐</b>이다.
 */
@Component
@Order(RequiredOperationalDataCheck.ORDER)
public class RequiredOperationalDataCheck implements ApplicationRunner {

	/** {@link DevServiceConfigSeed} 가 채운 <b>뒤에</b> 본다. 순서가 뒤집히면 dev 가 매번 걸린다. */
	static final int ORDER = DevServiceConfigSeed.ORDER + 100;

	private static final Logger log = LoggerFactory.getLogger(RequiredOperationalDataCheck.class);

	private final AiNoticeQuery notices;

	private final ConsentTermsQuery terms;

	private final Environment environment;

	public RequiredOperationalDataCheck(AiNoticeQuery notices, ConsentTermsQuery terms, Environment environment) {
		this.notices = notices;
		this.terms = terms;
		this.environment = environment;
	}

	/**
	 * @throws IllegalStateException {@code prod} 인데 필수 운영 데이터가 없다. <b>무엇이 없는지를
	 *     이름으로 적는다</b> — 값은 싣지 않는다 (S-3). 투입 절차는 {@code docs/deployment.md} §3
	 */
	@Override
	public void run(ApplicationArguments args) {
		List<String> missing = RequiredOperationalData.missing(this.notices, this.terms);
		if (missing.isEmpty()) {
			return;
		}
		// 자리 이름만 남긴다 — 문구도 판본도 로그에 싣지 않는다 (S-3).
		log.error("service.config.required.missing keys={} — 운영 데이터를 먼저 넣는다 (docs/deployment.md §3 의 0번 단계)",
				missing);
		if (this.environment.matchesProfiles("prod")) {
			throw new IllegalStateException(
					"필수 운영 데이터가 없다: " + String.join(", ", missing)
							+ " — docs/deployment.md §3 의 0번 단계를 먼저 수행한다");
		}
	}
}
