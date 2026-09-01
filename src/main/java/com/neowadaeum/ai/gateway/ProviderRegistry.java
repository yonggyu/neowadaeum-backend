package com.neowadaeum.ai.gateway;

import com.neowadaeum.ai.provider.StoryProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 등록된 Provider 어댑터 색인 (R3.1, B-18).
 *
 * <p><b>어댑터만 들어온다. 게이트웨이는 어댑터가 아니다.</b> {@link AiGateway} 도
 * {@code StoryProvider} 를 구현하므로, 이 목록을 스프링이 채울 때 게이트웨이 자신이 섞이면 색인이
 * 자기를 가리킨다. 배선이 그렇게 되지 않는다는 것은 {@code AiGatewayWiringTests} 가 확인한다.
 *
 * <p><b>고르지 못하는 상황에서 임의로 고르지 않는다.</b> 지목한 id 가 없거나, 활성 지정 없이 어댑터가
 * 둘 이상이면 예외를 던져 <b>부팅을 멈춘다</b>. 조용히 하나를 골라 두면 운영에서 어느 모델이 도는지
 * 아무도 모르는 상태가 되고, 그 사고는 에러가 아니라 "이상한 이야기"로 나타난다 (#72 와 같은 성질).
 */
public class ProviderRegistry {

	private final Map<String, StoryProvider> byId;

	public ProviderRegistry(List<StoryProvider> adapters) {
		this.byId = index(adapters);
	}

	/** 등록된 {@code providerId} 들. 오류 메시지와 테스트가 읽는다. */
	public Set<String> registeredIds() {
		return this.byId.keySet();
	}

	/**
	 * 설정이 지목한 Provider 를 고른다 (R3.1).
	 *
	 * @param active 활성 Provider 의 id. 지정하지 않았으면 {@code null}
	 * @throws IllegalStateException 지목한 id 가 등록되어 있지 않거나, 지정 없이 어댑터가 둘 이상일 때
	 */
	public StoryProvider select(String active) {
		if (active != null) {
			StoryProvider selected = this.byId.get(active);
			if (selected == null) {
				throw new IllegalStateException(
						"ai.provider.active=%s is not registered. registered: %s".formatted(active, registeredIds()));
			}
			return selected;
		}

		if (this.byId.size() > 1) {
			throw new IllegalStateException(
					"ai.provider.active must be set when more than one provider is registered. registered: %s"
							.formatted(registeredIds()));
		}
		return this.byId.values().iterator().next();
	}

	private static Map<String, StoryProvider> index(List<StoryProvider> adapters) {
		if (adapters == null || adapters.isEmpty()) {
			// 파이프라인이 Provider 없이 돌 수는 없다. 뜨지 않는 편이 안전하다 (#72).
			throw new IllegalStateException("no StoryProvider is registered");
		}

		Map<String, StoryProvider> indexed = new LinkedHashMap<>();
		for (StoryProvider adapter : adapters) {
			StoryProvider previous = indexed.put(adapter.providerId(), adapter);
			if (previous != null) {
				// 같은 id 가 둘이면 "어느 쪽이 이기는가"를 빈 등록 순서에 맡기게 된다.
				throw new IllegalStateException("duplicate providerId: " + adapter.providerId());
			}
		}
		return Map.copyOf(indexed);
	}
}
