package com.neowadaeum.ai.provider;

import com.neowadaeum.play.port.SummaryRequest;

/**
 * 턴 생성만 구현한 테스트용 Provider (B-18).
 *
 * <p>§3 의 seam 은 다섯 메서드다 (B-30 이 판정을 더했다). 턴만 보는 테스트가 매번 나머지 셋을 손으로 채우면, 그 자리에
 * <b>"일단 통과시키는" 구현이 들어가기 쉽다</b> — 빈 문자열을 돌려주는 {@code summarize} 하나면
 * 요약 파이프라인이 없는데도 초록이 된다. 여기서 한 번만 예외로 못박아 두고 재사용한다 (§0.2).
 */
public abstract class TurnOnlyStoryProvider implements StoryProvider {

	@Override
	public ProviderCapabilities capabilities() {
		return ProviderCapabilities.withoutModel();
	}

	@Override
	public java.util.Set<com.neowadaeum.common.spi.SafetyCategory> classifySafety(
			com.neowadaeum.common.spi.SafetyClassificationRequest request) {
		throw new UnsupportedOperationException("this test provider only generates turns");
	}

	@Override
	public String summarize(SummaryRequest request) {
		throw new UnsupportedOperationException("this test provider only generates turns");
	}

	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		throw new UnsupportedOperationException("this test provider only generates turns");
	}
}
