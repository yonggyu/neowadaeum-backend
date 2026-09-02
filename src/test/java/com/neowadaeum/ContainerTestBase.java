package com.neowadaeum;

import com.neowadaeum.catalog.domain.ServiceConfig;
import com.neowadaeum.catalog.repository.ServiceConfigRepository;
import com.neowadaeum.identity.auth.AuthTokenService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

/**
 * 컨테이너가 필요한 통합 테스트의 <b>단일 진입점</b>. 이런 테스트는 이 클래스를 상속한다.
 *
 * <p><b>왜 베이스 클래스인가 — 컨텍스트 1벌 규칙.</b> Spring TestContext 는 테스트 클래스의 애노테이션
 * 구성으로 컨텍스트 캐시 키를 만든다. 클래스마다 {@code @TestPropertySource} 나 {@code @MockitoBean} 을
 * 하나씩 더 붙이면 키가 갈라지고, <b>그 수만큼 컨텍스트가 새로 뜬다.</b> 컨텍스트 기동은 이 프로젝트에서
 * 가장 비싼 고정 비용이므로(측정: ext4 6.2초 / 9p 27.8초) 배수로 붙으면 곧바로 체감된다.
 *
 * <p>클래스가 3개인 지금은 티가 나지 않는다. B-32(턴 오케스트레이터) 이후 통합 테스트가 늘면 급격해진다.
 * 그래서 지금 고정한다.
 *
 * <p><b>여기서 갈라져야 한다면</b> — 특정 테스트만 다른 프로퍼티가 필요하다면, 애노테이션을 그 클래스에
 * 붙이지 말고 <b>왜 필요한지와 함께</b> 이 클래스에 반영하거나 별도 베이스 클래스를 만든다. 컨텍스트가
 * 한 벌 더 뜬다는 사실이 리뷰에 보여야 한다.
 *
 * <p><b>{@code dev} 프로파일로 돈다.</b> 결정론 Provider(S-3)와 dev 콘솔(B-47)·계약 문서(B-06)가
 * 그 프로파일에서만 존재하기 때문이다. <b>인증은 프로파일과 무관하다</b> — B-12 가 고정
 * {@code player_ref} 우회를 제거했으므로 {@code dev} 에서도 토큰이 필요하다 (#34).
 *
 * <p>그래서 {@link #asPlayer()} 를 둔다. <b>실제 발급기가 만든 토큰을 실제 헤더로</b> 보낸다 —
 * SecurityContext 를 직접 채우면 인증 필터가 도는지는 확인되지 않는다.
 *
 * <p>{@code @AutoConfigureMockMvc} 도 여기에 둔다. 컨트롤러 테스트마다 붙이면 캐시 키가 갈라져
 * 컨텍스트가 그 수만큼 더 뜬다 — 위와 같은 이유다.
 *
 * @see TestcontainersConfiguration
 */
@Tag("container")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@SpringBootTest
public abstract class ContainerTestBase {

	/**
	 * 테스트가 기본으로 쓰는 회원.
	 *
	 * <p>고정 값인 것은 편의다 — <b>인증 우회가 아니다.</b> 이 값으로 토큰을 만들려면 서명 키가
	 * 필요하고, 서버는 그 토큰을 다른 요청과 똑같이 검증한다.
	 */
	protected static final UUID TEST_PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-000000000001");

	@Autowired
	private AuthTokenService authTokens;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private ServiceConfigRepository serviceConfigs;

	/** {@code service_config} 의 고지 키. 값의 모양은 {@code CatalogAiNoticeQuery} 가 정한다. */
	protected static final String NOTICE_KEY = "ai.notice";

	/** 시드 문구. <b>운영 문구가 아니다</b> — 테스트가 값으로 대조하기 위한 것뿐이다. */
	protected static final String NOTICE = "이 이야기는 AI가 생성합니다.";

	/**
	 * 호출 한도 카운터를 테스트마다 비운다 (B-38).
	 *
	 * <p>컨텍스트가 한 벌이므로 <b>앞 테스트가 쓴 창이 다음 테스트에 남는다.</b> 한도는 계정
	 * 기준이고 테스트는 대부분 같은 회원을 쓰므로, 비우지 않으면 실행 순서에 따라 429 가 난다.
	 *
	 * <p>지우는 것은 {@code rate:} 접두어뿐이다 — 멱등 키나 락까지 지우면 그것을 검증하는
	 * 테스트가 무의미해진다.
	 */
	@BeforeEach
	void resetRateLimitWindows() {
		Set<String> keys = this.redis.keys("rate:*");
		if (keys != null && !keys.isEmpty()) {
			this.redis.delete(keys);
		}
	}

	/**
	 * 고지 문구를 심는다 (R11.1, #281).
	 *
	 * <p><b>여기 있는 이유</b> — 고지를 요구하는 화면이 일곱이 되면서, 세션을 시작하거나 턴을
	 * 진행하는 <b>거의 모든 통합 테스트</b>가 이 설정을 필요로 하게 됐다. 클래스마다 심으면
	 * 새 테스트가 그것을 빠뜨렸을 때 <b>500 의 원인이 고지라는 사실이 드러나지 않는다.</b>
	 *
	 * <p><b>없는 경우를 검증하는 테스트는 스스로 지운다.</b> 그쪽이 예외이며, 지우는 범위는
	 * 이 키 하나여야 한다 — {@code service_config} 를 통째로 비우면 다른 테스트가 원인 없이
	 * 깨진다 (이슈 #272).
	 */
	@BeforeEach
	void configureAiNotice() {
		this.serviceConfigs.save(ServiceConfig.of(NOTICE_KEY,
				"{\"version\":\"2026-07-21\",\"text\":\"%s\"}".formatted(NOTICE),
				Instant.parse("2026-08-27T00:00:00Z")));
	}

	/** 기본 회원으로 요청한다. */
	protected RequestPostProcessor asPlayer() {
		return asPlayer(TEST_PLAYER_REF);
	}

	/** 지정한 회원으로 요청한다. 소유자 판정 테스트가 쓴다. */
	protected RequestPostProcessor asPlayer(UUID playerRef) {
		String token = this.authTokens.issue(playerRef).accessToken();
		return request -> {
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			return request;
		};
	}
}
