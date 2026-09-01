package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.report.ReportService;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.support.Sha256;
import com.neowadaeum.common.web.PlayerRefResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 신고 (§13.9, R13.5, 세이프티 L3).
 *
 * <p><b>{@code 202} 다.</b> 접수했다는 뜻이지 조치했다는 뜻이 아니다 — 임계에 닿았는지를
 * 응답으로 알리면 신고자가 <b>임계를 역산할 수 있다</b> (S-11).
 *
 * <p><b>본문을 되돌려주지 않는다.</b> 무엇을 신고했는지, 지금 몇 건인지, 작품이 내려갔는지
 * 전부 담지 않는다. 신고자가 알아야 하는 것은 <b>접수됐다</b> 하나다.
 *
 * <p><b>IP 로도 센다</b> (S-8). 계정 기준만으로는 계정을 여러 개 만들어 임계를 채우는 길이
 * 열려 있다 — 신고는 <b>작품을 내릴 수 있는</b> 요청이므로 그 길을 좁힌다.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

	/** 한도의 종류. 계정 기준과 IP 기준이 섞이지 않게 한다. */
	private static final String IP_SCOPE = "report-ip";

	private final ReportService reports;

	private final PlayerRefResolver playerRefs;

	private final RateLimiter rateLimiter;

	private final RateLimitProperties limits;

	public ReportController(ReportService reports, PlayerRefResolver playerRefs,
			RateLimiter rateLimiter, RateLimitProperties limits) {
		this.reports = reports;
		this.playerRefs = playerRefs;
		this.rateLimiter = rateLimiter;
		this.limits = limits;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void report(@Valid @RequestBody ReportRequest body, HttpServletRequest request) {
		requireWithinLimit(request);

		this.reports.report(this.playerRefs.currentPlayerRef(), body.targetType(), body.targetId(),
				body.sessionId(), body.turnNo(), body.reason(), body.detail());
	}

	/**
	 * <b>IP 기준이다</b> (S-8).
	 *
	 * <p>인증된 경로지만 계정 기준만으로는 부족하다 — 계정을 여러 개 만들면 유일 제약도 임계도
	 * 함께 뚫린다. 회선 하나가 낼 수 있는 신고 수를 함께 묶는다.
	 *
	 * <p><b>원문 IP 를 넘기지 않는다</b> (§12) — 해시가 키다.
	 */
	private void requireWithinLimit(HttpServletRequest request) {
		if (!this.rateLimiter.tryAcquire(IP_SCOPE, Sha256.hex(request.getRemoteAddr()),
				this.limits.reportPerMinutePerIp(), RateLimitProperties.MINUTE)) {
			throw new ApiException(ErrorCode.RATE_LIMITED,
					Map.of("retryAfterSeconds", RateLimitProperties.MINUTE.toSeconds()));
		}
	}
}
