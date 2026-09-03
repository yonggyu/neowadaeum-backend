package com.neowadaeum.identity.api;

import com.neowadaeum.common.web.PublicReadGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 약관 메타 API (이슈 #261).
 *
 * <p><b>가입 화면이 판본을 상수로 들고 있지 않게 하는 것</b>이 이 경로의 존재 이유다. 화면은
 * 여기서 읽은 {@code version} 을 가입 요청에 그대로 되돌려 보낸다 — 약관이 개정돼도 프론트를
 * 다시 배포할 필요가 없고, <b>동의 이력에 옛 판본이 남지 않는다</b> (R10.2).
 *
 * <p><b>{@link com.neowadaeum.common.web.PlayerRefResolver} 를 받지 않는다.</b> 가입 <b>전에</b>
 * 불리는 경로이며(계약의 {@code security: []}), 받으면 인증되지 않은 요청에서 401 을 던진다 —
 * {@code LandingController} 와 같은 이유다.
 *
 * <p>그래서 <b>{@link PublicReadGuard} 를 받는다</b> (S-8, 이슈 #277). 셀 계정이 없는 경로이므로
 * IP 로 세며, <b>{@code /landing} 과 같은 창을 쓴다</b> — 따로 세면 한쪽이 다른 쪽의 우회로가 된다.
 */
@RestController
public class ConsentTermsController {

	private final ConsentTermsService terms;

	private final PublicReadGuard publicReads;

	public ConsentTermsController(ConsentTermsService terms, PublicReadGuard publicReads) {
		this.terms = terms;
		this.publicReads = publicReads;
	}

	@GetMapping("/api/v1/consents")
	public ConsentTermsView consents(HttpServletRequest request) {
		this.publicReads.requireWithinIpLimit(request);
		return this.terms.terms();
	}
}
