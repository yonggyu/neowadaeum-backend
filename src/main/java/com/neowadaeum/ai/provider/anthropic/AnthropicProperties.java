package com.neowadaeum.ai.provider.anthropic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic 어댑터 접속 정보 (B-22, R3.1).
 *
 * <p><b>기본값을 주지 않는다</b> (§7.3). {@code ${VAR:실제값}} 패턴은 값이 빠진 배포를 조용히
 * 뜨게 만들고, 그때 나가는 것은 <b>누군가의 키</b>이거나 <b>엉뚱한 모델</b>이다. 값이 없으면
 * 어댑터가 등록되지 않고, 그것이 활성 Provider 였다면 부팅이 멈춘다 ({@code ProviderRegistry}).
 *
 * <p><b>{@code baseUrl} 이 설정인 이유는 테스트다.</b> 계약 테스트가 고정 응답 서버를 가리켜야
 * 하며, 그 값을 코드에 박으면 테스트가 실제 API 를 부르거나 리플렉션으로 뜯어야 한다
 * ({@code .claude/rules/testing.md} — 테스트에서 실제 AI 를 호출하지 않는다).
 *
 * @param apiKey  {@code x-api-key} 헤더 값. <b>로그에 남기지 않는다</b> (S-3)
 * @param model   {@code model} 필드. 용도별 분리는 B-24 다 — 지금은 턴 생성 하나뿐이다 (R3.6)
 * @param baseUrl API 기점. 운영에서는 바꿀 이유가 없다
 * @param maxTokens 응답 상한. 턴 하나는 문단 3~5개라 크게 잡을 이유가 없다 (R5.3)
 */
@ConfigurationProperties("ai.providers.anthropic")
public record AnthropicProperties(String apiKey, String model, String baseUrl, Integer maxTokens) {

	/** §5.2 의 출력은 문단 3~5개와 선택지 1~4개다. 그보다 크게 잡으면 사고가 비싸진다. */
	private static final int DEFAULT_MAX_TOKENS = 4096;

	private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

	public AnthropicProperties {
		baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
		maxTokens = (maxTokens == null) ? DEFAULT_MAX_TOKENS : maxTokens;
	}

	/**
	 * 이 어댑터를 등록할 수 있는가.
	 *
	 * <p><b>키와 모델이 둘 다 있어야 한다.</b> 하나만 있는 상태는 설정을 하다 만 것이며, 그 상태로
	 * 등록되면 <b>첫 턴 요청에서야</b> 드러난다.
	 */
	public boolean configured() {
		return this.apiKey != null && !this.apiKey.isBlank()
				&& this.model != null && !this.model.isBlank();
	}
}
