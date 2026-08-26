package com.neowadaeum.ai.safety;

import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.common.spi.SafetyCategory;
import com.neowadaeum.common.spi.SafetyClassificationFailedException;
import com.neowadaeum.common.spi.SafetyClassificationRequest;
import com.neowadaeum.common.spi.SafetyClassifier;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code safety} 가 쓰는 분류 SPI 의 {@code ai} 쪽 구현 (B-30).
 *
 * <p><b>방향이 요점이다.</b> {@code safety} 는 {@code ai} 를 참조하지 않고, {@code ai} 가
 * {@code common} 의 인터페이스를 구현해 그 자리를 채운다 (§5.4, ADR-0002 와 같은 형태). 그래서
 * provider 를 바꿔도 판정기는 바뀌지 않는다 (I-13).
 *
 * <p><b>모든 실패를 {@link SafetyClassificationFailedException} 으로 좁힌다.</b> 호출자가 알아야
 * 하는 것은 "판정하지 못했다" 하나이며, 그 답은 통과가 아니라 차단이다 (fail-closed). 벤더 장애든
 * 시간 초과든 미구현 어댑터든 결론이 같으므로 {@code safety} 가 {@code ai} 의 예외 종류를 알아야
 * 할 이유가 없다 — 알게 되면 그것이 곧 참조다.
 */
public class ProviderSafetyClassifier implements SafetyClassifier {

	private static final Logger log = LoggerFactory.getLogger(ProviderSafetyClassifier.class);

	private final StoryProvider provider;

	public ProviderSafetyClassifier(StoryProvider provider) {
		if (provider == null) {
			throw new IllegalStateException("the safety classifier needs a provider");
		}
		this.provider = provider;
	}

	@Override
	public Set<SafetyCategory> classify(SafetyClassificationRequest request) {
		try {
			return this.provider.classifySafety(request);
		}
		catch (SafetyClassificationFailedException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// 판정 대상 원문도 응답 원문도 남기지 않는다 (S-3). 남기는 것은 실패했다는 사실까지다.
			log.warn("safety classification did not complete; treating it as a closed failure");
			throw new SafetyClassificationFailedException("safety classification did not complete");
		}
	}
}
