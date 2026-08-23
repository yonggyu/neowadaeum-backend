package com.neowadaeum;

import org.junit.jupiter.api.Tag;
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
 * <p><b>{@code dev} 프로파일로 돈다.</b> 슬라이스는 인증(B-07 · B-12)을 제외하고 {@code dev} 고정
 * {@code player_ref} 로 대체했다(ADR-0004). 그 구현은 {@code @Profile("dev")} 이고 컨트롤러가 그것을
 * <b>필수 인자로</b> 받으므로, 프로파일이 없으면 컨텍스트 자체가 뜨지 않는다 — 실제로 이 애노테이션을
 * 붙이기 전에 그렇게 확인했다.
 *
 * <p><b>그 실패가 버그가 아니라 설계다.</b> 인증 없이 플레이 경로가 열리는 것을 막는 마지막 장치이며,
 * {@code prod} 와 무프로파일에서 우회 빈이 만들어지지 않는다는 것은
 * {@code DevPlayerRefBypassTests} 가 따로 검증한다 (#34).
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
}
