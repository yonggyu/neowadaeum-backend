package com.neowadaeum.identity.auth;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리자 접근 제한 (S-4, R14.6).
 *
 * <p><b>허용목록이 비어 있으면 아무도 통과하지 못한다.</b> 비어 있음을 "제한 없음"으로 읽으면
 * <b>설정 누락이 전면 허용이 된다</b> — 그것이 이 클래스에서 가장 중요한 한 줄이다.
 *
 * <p><b>기본값을 두지 않는다</b> (§7.3). 그리고 <b>실제 IP 를 레포에 적지 않는다</b> (S-11) —
 * 값은 배포 환경에만 있다.
 *
 * @param allowedIps 허용할 접속 주소. 비면 관리자 경로가 통째로 닫힌다
 */
@ConfigurationProperties("admin.access")
public record AdminAccessProperties(List<String> allowedIps) {

	public AdminAccessProperties {
		allowedIps = List.copyOf(allowedIps == null ? List.of() : allowedIps);
	}

	/**
	 * <b>허용목록에 있는가.</b>
	 *
	 * <p>비어 있으면 언제나 {@code false} 다 — 설정하지 않은 상태에서 관리자 경로가 열리지 않는다.
	 */
	public boolean allows(String remoteAddress) {
		return remoteAddress != null && Set.copyOf(this.allowedIps).contains(remoteAddress);
	}
}
