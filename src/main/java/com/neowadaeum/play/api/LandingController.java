package com.neowadaeum.play.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 랜딩 API (§13.10).
 *
 * <p><b>{@link com.neowadaeum.common.web.PlayerRefResolver} 를 받지 않는다.</b> 인증 없이 열리는
 * 경로이며(계약의 {@code security: []}), 받으면 인증되지 않은 요청에서 401 을 던진다.
 */
@RestController
public class LandingController {

	private final LandingService landing;

	public LandingController(LandingService landing) {
		this.landing = landing;
	}

	@GetMapping("/api/v1/landing")
	public LandingView landing() {
		return this.landing.landing();
	}
}
