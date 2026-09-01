package com.neowadaeum.authoring.report;

import com.neowadaeum.common.spi.ServiceConfigQuery;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 몇 건이면 내리는가 (R8.9, §13-12).
 *
 * <p><b>값이 코드에 없다.</b> 운영 중에 배포 없이 고칠 수 있어야 하기 때문이다 — 오탐이
 * 몰리거나 큐가 감당되지 않을 때 재배포를 기다릴 수는 없다. 그리고 <b>값을 알면 그 아래로
 * 관리할 수 있다</b> (S-11).
 *
 * <p><b>공개 범위마다 다르다</b> (§13-12). 사전 검수가 약한 쪽을 사후에 더 본다 — 그것이
 * {@code unlisted} 에 인간 검수를 걸지 않기로 한 선택의 대가를 치르는 방법이다.
 *
 * <p><b>설정이 없으면 내리지 않는다.</b> 임의의 기본값을 코드에 두면 그 값이 곧 정책이 되고,
 * 아무도 그것을 정한 적이 없다. 대신 <b>설정되지 않았다는 사실</b>이 판정 결과에 남는다
 * (§13-41) — 조용히 넘어가지 않는다.
 */
@Component
public class SuspensionThresholds {

	/**
	 * 값이 담기는 자리. 형태는 공개 범위 → 건수다.
	 *
	 * <p><b>키만 공개다.</b> 값은 운영이 넣는다 — 그것이 이 클래스의 존재 이유다.
	 */
	public static final String CONFIG_KEY = "ugc.report.suspend_threshold";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ServiceConfigQuery configs;

	public SuspensionThresholds(ServiceConfigQuery configs) {
		this.configs = configs;
	}

	/**
	 * @param visibility 작품의 공개 범위 (`private` / `unlisted` / `public`)
	 * @return 그 범위의 임계. <b>설정되지 않았으면 비어 있다</b>
	 */
	public java.util.OptionalInt forVisibility(String visibility) {
		return this.configs.find(CONFIG_KEY).map(json -> thresholdIn(json, visibility))
				.orElse(java.util.OptionalInt.empty());
	}

	/**
	 * <b>모르는 표기는 임계 없음이다.</b> 0 으로 읽으면 신고 한 건도 없이 내려간다 — 파싱
	 * 실패가 정지로 둔갑하는 것은 어떤 오탐보다 나쁘다.
	 */
	private static java.util.OptionalInt thresholdIn(String json, String visibility) {
		JsonNode node = JSON.readTree(json).path(visibility.toLowerCase(Locale.ROOT));
		if (!node.isIntegralNumber() || node.asInt() < 1) {
			return java.util.OptionalInt.empty();
		}
		return java.util.OptionalInt.of(node.asInt());
	}
}
