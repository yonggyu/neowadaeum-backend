package com.neowadaeum;

import com.neowadaeum.identity.auth.AuthTokenService;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
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
