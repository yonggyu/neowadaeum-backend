package com.neowadaeum.common.web;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.support.RateLimitProperties;
import com.neowadaeum.common.support.RateLimiter;
import com.neowadaeum.common.support.Sha256;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 인증 없이 열리는 설정 조회의 호출 한도 (S-8, 이슈 #277).
 *
 * <p><b>대상은 {@code GET /landing} 과 {@code GET /consents} 다</b> (§13.10, #261). 둘 다 인증
 * <b>이전에</b> 불리고, 값이 {@code service_config} 에서 오며, <b>캐시가 없다</b> — 캐시를 두지
 * 않은 것은 "배포 없이 갱신"을 위한 의도된 선택이고(이유는 {@code CatalogServiceConfigQuery} 에
 * 적혀 있다), 그래서 <b>요청 수가 곧 DB 읽기 수</b>다. 한도가 없으면 토큰 없이 그 읽기를
 * 무제한으로 만들 수 있다.
 *
 * <p><b>한 클래스인 이유가 요점이다.</b> 두 경로가 <b>같은 창을 공유한다</b> — 각자 세면 한쪽을
 * 다 쓴 뒤 다른 쪽으로 옮겨 가면 되고, 그러면 한도는 절반짜리가 된다. 축을 하나로 둔다는 결정이
 * 두 모듈에 흩어져 있으면 <b>둘 중 하나가 조용히 달라지는 날</b>이 온다.
 *
 * <p><b>계정이 아니라 IP 로 센다</b> (S-8) — 인증 전이라 셀 계정이 없다. <b>해시로만</b> 센다
 * (§12): 키에 원문을 넣으면 Redis 가 접속자 목록이 된다.
 *
 * <p><b>프록시 뒤에서는 {@code getRemoteAddr()} 이 프록시를 가리킨다.</b> 전달 헤더를 신뢰된
 * 프록시에서 온 것만 신뢰한다는 원칙은 §13-45 이고, 그것을 실제 값으로 세우는 것은 배포 환경이
 * 정해지는 시점이다 ({@code docs/deployment.md} §5, 이슈 #224) — {@code AuthController} 의
 * IP 기준 한도와 같은 조건이다.
 *
 * <p><b>탐색은 창이 다르다</b> (§13-54, 이슈 #306). 라이브러리와 작품 상세도 인증 밖으로
 * 열렸지만 <b>같은 창에 넣지 않았다.</b> 위의 "합치는" 논리는 두 경로가 <b>같은 것을 지키고
 * 같은 빈도로 불릴 때</b> 성립한다 — 여기서는 둘 다 아니다. 탐색은 한 화면이 섹션 더 보기와
 * 작품 상세로 이어져 <b>몇 분에 수십 번</b> 불리고, 설정 조회는 화면당 한 번이다. 한 창에 넣으면
 * <b>둘러보던 사람이 창을 다 쓴 뒤 가입하지 못한다</b> — {@code /consents} 가 막히면 약관 판본을
 * 읽을 수 없고 가입 요청이 성립하지 않는다. 우회로가 되지 않는 이유는 <b>지키는 대상이 다르기
 * 때문</b>이다: 탐색 창을 다 써도 설정 조회의 DB 읽기는 여전히 60/분으로 묶여 있다.
 */
@Component
public class PublicReadGuard {

	/** 한도의 종류. <b>두 경로가 이 하나를 공유한다</b>(위 설명). 계정 기준 창과도 섞이지 않는다. */
	static final String SCOPE = "public-read-ip";

	/**
	 * 탐색의 창 (§13-54, 이슈 #306). <b>설정 조회와 나뉜다</b> — 이유는 위 설명에 있다.
	 *
	 * <p>라이브러리 · 섹션 · 작품 상세 <b>셋이 이 하나를 공유한다.</b> 셋은 같은 화면 흐름이고
	 * 같은 catalog 읽기라서, 따로 세면 한 축을 다 쓴 뒤 다른 축으로 옮겨 가면 된다.
	 */
	static final String BROWSE_SCOPE = "public-browse-ip";

	private final RateLimiter rateLimiter;

	private final RateLimitProperties limits;

	public PublicReadGuard(RateLimiter rateLimiter, RateLimitProperties limits) {
		this.rateLimiter = rateLimiter;
		this.limits = limits;
	}

	/**
	 * 한 번 센다. 넘겼으면 요청을 여기서 끊는다.
	 *
	 * <p><b>주소를 알 수 없으면 세지 않는다.</b> 서블릿 컨테이너가 소켓에서 채우므로 실제로는
	 * 비지 않으며, 알 수 없는 것을 전부 한 칸에 몰아넣으면 그 칸이 차는 순간 <b>그런 요청끼리
	 * 서로를 막는다.</b> {@code AuthController} 가 같은 선택을 했다.
	 *
	 * @throws ApiException {@code RATE_LIMITED} — {@code details.retryAfterSeconds} 를 함께 준다.
	 *     언제 다시 올지 알려주지 않으면 클라이언트가 즉시 재시도한다
	 */
	public void requireWithinIpLimit(HttpServletRequest request) {
		count(SCOPE, this.limits.publicReadPerMinutePerIp(), request);
	}

	/**
	 * 인증 밖으로 열린 <b>탐색</b>을 한 번 센다 (§13-54, 이슈 #306).
	 *
	 * <p>세는 방식은 설정 조회와 같다 — IP 해시 · 분 창 · {@code RATE_LIMITED}. <b>창과 한도만</b>
	 * 다르며 그 이유는 이 클래스의 설명에 있다.
	 *
	 * @throws ApiException {@code RATE_LIMITED} — {@code details.retryAfterSeconds} 를 함께 준다
	 */
	public void requireWithinBrowseIpLimit(HttpServletRequest request) {
		count(BROWSE_SCOPE, this.limits.publicBrowsePerMinutePerIp(), request);
	}

	private void count(String scope, int limit, HttpServletRequest request) {
		String ipHash = Sha256.hex(request.getRemoteAddr());
		if (ipHash == null) {
			return;
		}
		if (!this.rateLimiter.tryAcquire(scope, ipHash, limit, RateLimitProperties.MINUTE)) {
			throw new ApiException(ErrorCode.RATE_LIMITED, Map.of("retryAfterSeconds",
					this.rateLimiter.retryAfterSeconds(RateLimitProperties.MINUTE)));
		}
	}
}
