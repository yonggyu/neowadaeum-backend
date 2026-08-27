package com.neowadaeum.identity.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * 관리자 접근 제한 (S-4, R14.6).
 *
 * <p><b>허용목록이 비어 있으면 아무도 통과하지 못한다.</b> 비어 있음을 "제한 없음"으로 읽으면
 * <b>설정 누락이 전면 허용이 된다</b> — 그 방향의 실수는 되돌릴 수 없다.
 *
 * <p>설정 키는 B-01 이 둔 {@code admin.allowed-cidr} 그대로다. 새 키를 만들면 <b>같은 것을
 * 가리키는 값이 둘</b>이 되고, 그중 하나만 채운 배포가 생긴다.
 *
 * <p><b>대역을 받는다.</b> 운영 접속은 대개 단일 주소가 아니라 대역이며, 매칭은 Spring Security 의
 * {@code IpAddressMatcher} 가 한다 — IPv6 와 접두 길이를 직접 다루지 않는다.
 *
 * <p><b>실제 주소를 레포에 적지 않는다</b> (S-11). 값은 배포 환경에만 있다.
 *
 * @param allowedCidr 허용할 대역 또는 단일 주소. 비면 관리자 경로가 통째로 닫힌다
 */
@ConfigurationProperties("admin")
public record AdminAccessProperties(List<String> allowedCidr) {

	public AdminAccessProperties {
		allowedCidr = List.copyOf(allowedCidr == null ? List.of() : allowedCidr);
	}

	/**
	 * <b>허용 대역 안인가.</b>
	 *
	 * <p>비어 있으면 언제나 {@code false} 다. 해석되지 않는 항목도 통과시키지 않는다 —
	 * 잘못 적은 대역이 <b>모두 허용</b>으로 읽히면 안 된다.
	 */
	public boolean allows(String remoteAddress) {
		if (remoteAddress == null || this.allowedCidr.isEmpty()) {
			return false;
		}
		for (String cidr : this.allowedCidr) {
			try {
				if (new IpAddressMatcher(cidr.trim()).matches(remoteAddress)) {
					return true;
				}
			}
			catch (IllegalArgumentException ex) {
				// 잘못 적은 대역이다. 통과시키지 않는다 — 설정 오류가 전면 허용이 되면 안 된다.
			}
		}
		return false;
	}
}
