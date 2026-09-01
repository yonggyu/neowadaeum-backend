package com.neowadaeum.safety.l1;

import com.neowadaeum.common.spi.SafetyCategory;
import java.util.Set;

/**
 * 입력 검수 결과 (B-43).
 *
 * <p><b>{@code safety.l2} 의 타입을 그대로 내주지 않는다.</b> 내주면 입력 검수를 쓰는 쪽이
 * 출력 검수의 API 까지 끌어오게 되고, 둘 중 하나가 바뀔 때 다른 쪽이 흔들린다.
 *
 * @param blocked 들이지 않는다
 * @param categories 걸린 분류. <b>사유 문구가 아니라 분류만</b> 담는다 — 무엇에 걸렸는지를
 *     문자열로 돌려주면 그것이 곧 우회의 단서다 (S-6, S-11)
 */
public record InputVerdict(boolean blocked, Set<SafetyCategory> categories) {

	public InputVerdict {
		categories = Set.copyOf(categories == null ? Set.of() : categories);
	}

	public static InputVerdict pass() {
		return new InputVerdict(false, Set.of());
	}
}
