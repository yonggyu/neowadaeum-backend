package com.neowadaeum.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.web.PlayerRefResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * <b>#34 조건 3 — 인증 우회가 제거됐다.</b>
 *
 * <p>이 파일은 {@code DevPlayerRefBypassTests} 를 대체한다. 그쪽은 <i>"우회가 열려서는 안 되는
 * 곳에서 열리지 않는다"</i> 를 확인했고, 그 전제는 <b>우회가 존재한다</b>는 것이었다. 이제 존재하지
 * 않으므로 확인할 것이 바뀐다 — <b>어느 프로파일에서도 값의 출처가 토큰뿐인가.</b>
 *
 * <p>#34 는 <i>"남겨두면 편의가 자리를 잡고 제거할 이유가 약해진다"</i> 고 적었다. 그래서 제거를
 * 문장이 아니라 테스트로 못박는다 — {@code dev} 프로파일에서 고정 {@code playerRef} 빈이 <b>다시
 * 생기면</b> 여기가 빨개진다.
 */
class AuthBypassRemovalTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(TokenPlayerRefResolver.class);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	/**
	 * <b>{@code dev} 에서도 토큰 기반 리졸버 하나뿐이다.</b>
	 *
	 * <p>고정 {@code playerRef} 빈이 돌아오면 후보가 둘이 되어 이 단언이 깨진다.
	 */
	@Test
	void S34_dev_has_exactly_one_resolver_and_it_is_token_based() {
		this.runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
			assertThat(context).hasSingleBean(PlayerRefResolver.class);
			assertThat(context.getBean(PlayerRefResolver.class)).isInstanceOf(TokenPlayerRefResolver.class);
		});
	}

	/** 프로파일이 없어도, {@code prod} 에서도 같다 — 실제 인증에는 프로파일 조건이 없다. */
	@Test
	void S34_the_resolver_is_not_gated_by_any_profile() {
		this.runner.run(context -> assertThat(context).hasSingleBean(TokenPlayerRefResolver.class));
		this.runner.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).hasSingleBean(TokenPlayerRefResolver.class));
		this.runner.withPropertyValues("spring.profiles.active=prod,dev")
				.run(context -> assertThat(context).hasSingleBean(TokenPlayerRefResolver.class));
	}

	/**
	 * <b>인증되지 않은 요청은 값을 얻지 못한다.</b>
	 *
	 * <p>{@code null} 을 돌려주면 주인 없는 요청이 도메인에 들어가고, 그 뒤의 모든 판정이
	 * {@code null} 을 소유자로 다루게 된다.
	 */
	@Test
	void I3_an_unauthenticated_request_cannot_obtain_a_player_ref() {
		assertThatThrownBy(() -> new TokenPlayerRefResolver().currentPlayerRef())
				.isInstanceOf(ApiException.class);
	}

	/** 인증된 요청의 주체는 {@code playerRef} 다 — {@code user.id} 도 이메일도 아니다 (I-3). */
	@Test
	void I3_the_principal_is_the_player_ref() {
		UUID playerRef = UUID.randomUUID();
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(playerRef, null, List.of()));

		assertThat(new TokenPlayerRefResolver().currentPlayerRef()).isEqualTo(playerRef);
	}

	/**
	 * <b>주체가 {@code UUID} 가 아니면 거부한다.</b>
	 *
	 * <p>다른 인증 경로가 문자열 주체를 올려 두면 {@code UUID.fromString} 이 어딘가에서 터지거나,
	 * 더 나쁘게는 값이 조용히 다른 의미로 쓰인다.
	 */
	@Test
	void I3_a_principal_of_another_type_is_rejected() {
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated("someone", null, List.of()));

		assertThatThrownBy(() -> new TokenPlayerRefResolver().currentPlayerRef())
				.isInstanceOf(ApiException.class);
	}
}
