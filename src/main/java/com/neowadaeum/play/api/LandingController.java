package com.neowadaeum.play.api;

import com.neowadaeum.common.web.PublicReadGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 랜딩 API (§13.10).
 *
 * <p><b>{@link com.neowadaeum.common.web.PlayerRefResolver} 를 받지 않는다.</b> 인증 없이 열리는
 * 경로이며(계약의 {@code security: []}), 받으면 인증되지 않은 요청에서 401 을 던진다.
 *
 * <p>그래서 <b>{@link PublicReadGuard} 를 받는다</b> (S-8, 이슈 #277). 셀 계정이 없는 경로이므로
 * IP 로 세며, <b>{@code /consents} 와 같은 창을 쓴다</b> — 따로 세면 한쪽이 다른 쪽의 우회로가 된다.
 */
@RestController
public class LandingController {

	private final LandingService landing;

	private final PublicReadGuard publicReads;

	public LandingController(LandingService landing, PublicReadGuard publicReads) {
		this.landing = landing;
		this.publicReads = publicReads;
	}

	@GetMapping("/api/v1/landing")
	public LandingView landing(HttpServletRequest request) {
		this.publicReads.requireWithinIpLimit(request);
		return this.landing.landing();
	}
}
