package com.neowadaeum.authoring.review;

import com.neowadaeum.common.spi.ServiceConfigQuery;
import java.util.Locale;
import java.util.OptionalInt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인작 중 몇 퍼센트를 다시 보는가 (R8.11, §13-12).
 *
 * <p><b>값이 코드에 없다.</b> 검수 인력이 감당하는 양에 맞춰 조정되는 값이고, 그때마다
 * 재배포를 기다릴 수는 없다. 그리고 <b>비율을 알면 그 아래로 관리할 수 있다</b> (S-11).
 *
 * <p><b>공개 범위마다 다르다</b> (§13-12). 사전 검수가 약한 쪽을 사후에 더 본다 — 그것이
 * {@code unlisted} 에 인간 검수를 걸지 않기로 한 선택의 대가를 치르는 방법이다.
 *
 * <p><b>설정이 없으면 아무것도 뽑지 않는다.</b> 임의의 기본 비율을 코드에 두면 그 값이 곧
 * 정책이 되고, 아무도 그것을 정한 적이 없다 — 그리고 검수 큐가 조용히 차오른다.
 */
@Component
public class SamplingRates {

	/**
	 * 값이 담기는 자리. 형태는 공개 범위 → 백분율이다.
	 *
	 * <p><b>키만 공개다.</b> 값은 운영이 넣는다 — 그것이 이 클래스의 존재 이유다.
	 */
	public static final String CONFIG_KEY = "ugc.review.sampling_rate";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int FULL = 100;

	private final ServiceConfigQuery configs;

	public SamplingRates(ServiceConfigQuery configs) {
		this.configs = configs;
	}

	/**
	 * @param visibility 작품의 공개 범위
	 * @return 백분율. <b>설정되지 않았으면 비어 있다</b>
	 */
	public OptionalInt percentFor(String visibility) {
		return this.configs.find(CONFIG_KEY).map(json -> percentIn(json, visibility))
				.orElse(OptionalInt.empty());
	}

	/**
	 * <b>모르는 표기는 비율 없음이다.</b> 0 으로 읽으면 아무것도 뽑지 않으니 안전해 보이지만,
	 * 100 으로 읽으면 <b>승인작 전부가 큐에 쏟아진다</b> — 파싱 실패를 정책으로 바꾸지 않는다.
	 */
	private static OptionalInt percentIn(String json, String visibility) {
		JsonNode node = JSON.readTree(json).path(visibility.toLowerCase(Locale.ROOT));
		if (!node.isIntegralNumber() || node.asInt() < 1 || node.asInt() > FULL) {
			return OptionalInt.empty();
		}
		return OptionalInt.of(node.asInt());
	}
}
