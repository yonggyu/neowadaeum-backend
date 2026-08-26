package com.neowadaeum.ai.provider.anthropic;

import com.neowadaeum.ai.provider.AiPurpose;
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
 * <p><b>모델은 용도별이다</b> (R3.6, B-24). 하나로 두면 요약과 검수가 턴 생성과 같은 값을 쓰고,
 * <b>저비용으로 충분한 일에 고성능 모델이 붙는다.</b>
 *
 * @param models  용도별 모델 (R3.6). 네 용도가 서로 다른 값을 가질 수 있다
 * @param baseUrl API 기점. 운영에서는 바꿀 이유가 없다
 * @param maxTokens 응답 상한. 턴 하나는 문단 3~5개라 크게 잡을 이유가 없다 (R5.3)
 */
@ConfigurationProperties("ai.providers.anthropic")
public record AnthropicProperties(String apiKey, Models models, String baseUrl, Integer maxTokens) {

	/** §5.2 의 출력은 문단 3~5개와 선택지 1~4개다. 그보다 크게 잡으면 사고가 비싸진다. */
	private static final int DEFAULT_MAX_TOKENS = 4096;

	private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

	public AnthropicProperties {
		baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
		maxTokens = (maxTokens == null) ? DEFAULT_MAX_TOKENS : maxTokens;
		models = (models == null) ? new Models(null, null, null, null) : models;
	}

	/**
	 * 용도별 모델 (R3.6).
	 *
	 * <p><b>넷을 하나로 합치지 않는다.</b> "지금은 같은 값을 쓴다"는 것은 설정의 상태이지 구조가
	 * 아니다 — 합쳐 두면 나중에 나누는 일이 코드 변경이 된다.
	 *
	 * <p><b>기본값을 주지 않는다</b> (§7.3). 값이 없으면 그 용도가 <b>등록되지 않은 것</b>이며,
	 * 부르는 순간 드러난다. 조용히 다른 용도의 모델을 빌려 쓰지 않는다 — 그 사고는 비용 청구서로
	 * 나타나고, 그때는 이미 한 달치다.
	 *
	 * @param turn    턴 본문 생성. <b>고성능 모델</b>
	 * @param summary 요약 (B-34). 저비용
	 * @param safety  Safety L2 (B-30). <b>생성 모델과 별개 판정기여야 한다</b> (I-12)
	 * @param outline UGC 아웃라인 초안 (B-52). 저비용
	 */
	public record Models(String turn, String summary, String safety, String outline) {

		/** 용도 하나의 모델. 설정되지 않았으면 {@code null} 이다. */
		public String forPurpose(AiPurpose purpose) {
			return switch (purpose) {
				case TURN -> this.turn;
				case SUMMARY -> this.summary;
				case SAFETY -> this.safety;
				case OUTLINE -> this.outline;
			};
		}
	}

	/**
	 * 이 어댑터를 등록할 수 있는가.
	 *
	 * <p><b>키와 <em>턴 생성</em> 모델이 둘 다 있어야 한다.</b> 하나만 있는 상태는 설정을 하다 만
	 * 것이며, 그 상태로 등록되면 <b>첫 턴 요청에서야</b> 드러난다.
	 *
	 * <p>나머지 세 용도는 등록 조건이 아니다 — 요약(B-34)과 아웃라인(B-52)은 아직 구현되지 않았고,
	 * <b>없는 기능의 설정을 강요하면 그 자리에 아무 값이나 채워 넣게 된다.</b>
	 *
	 * <p><b>검수(B-30)는 구현됐지만 아직 등록 조건이 아니다.</b> 파이프라인이 판정을 부르기
	 * 시작하는 시점에 그것을 조일지 정한다 — 지금 조이면 <b>부르지도 않는 기능 때문에</b> 기존
	 * 설정이 부팅에 실패한다. 그때까지는 부르는 자리에서 실패한다({@link #modelFor}), 즉 조용히
	 * 턴 생성 모델을 빌려 쓰는 경로는 없다.
	 */
	public boolean configured() {
		return this.apiKey != null && !this.apiKey.isBlank() && modelFor(AiPurpose.TURN) != null;
	}

	/**
	 * 용도별 모델. 설정되지 않았으면 {@code null} 이다.
	 *
	 * <p>호출자가 {@code null} 을 만나는 지점은 <b>그 용도를 처음 쓰는 곳</b>이며, 거기서 실패하는
	 * 것이 조용히 턴 생성 모델을 빌려 쓰는 것보다 낫다.
	 */
	public String modelFor(AiPurpose purpose) {
		String model = this.models.forPurpose(purpose);
		return (model == null || model.isBlank()) ? null : model;
	}
}
