package com.neowadaeum.ai.provider.anthropic;

import com.neowadaeum.ai.provider.AiPurpose;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
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
 * @param pricing <b>모델 ID 별 KRW 단가</b> (#311, §13-53). 비어 있으면 그 모델의 비용을 모르는
 *                것이고, 그때 {@code cost_micro_krw} 는 {@code null} 이다
 */
@ConfigurationProperties("ai.providers.anthropic")
public record AnthropicProperties(String apiKey, Models models, String baseUrl, Integer maxTokens,
		Map<String, ModelPrice> pricing) {

	/** §5.2 의 출력은 문단 3~5개와 선택지 1~4개다. 그보다 크게 잡으면 사고가 비싸진다. */
	private static final int DEFAULT_MAX_TOKENS = 4096;

	private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

	public AnthropicProperties {
		baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
		maxTokens = (maxTokens == null) ? DEFAULT_MAX_TOKENS : maxTokens;
		models = (models == null) ? new Models(null, null, null, null) : models;
		pricing = (pricing == null) ? Map.of() : Map.copyOf(pricing);
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

	/**
	 * 호출 한 건의 비용 — <b>원(KRW)의 백만분의 1</b> (#311, §13-53).
	 *
	 * <p><b>모르면 {@code null} 이다.</b> 단가가 설정에 없거나 Provider 가 usage 를 주지 않으면
	 * 비용을 계산할 근거가 없다. 그 자리에 0 을 넣지 않는다 — 0 은 <b>"공짜로 돌았다"는 사실
	 * 진술</b>이고, 그것으로 만든 합계는 조용히 낮다. {@code null} 은 세지 않으므로 대시보드가
	 * "모른다"를 "0원"으로 읽지 않는다.
	 *
	 * <p><b>입력과 출력이 둘 다 있어야 센다.</b> 한쪽만으로 만든 수는 비용이 아니라 비용의 일부이며,
	 * 그것을 비용 칸에 넣으면 <b>틀린 줄 모르는 채 작은 값</b>이 쌓인다.
	 *
	 * <p><b>환율이 여기 없다.</b> 설정에 들어오는 단가가 이미 KRW 다 — 서버가 환율을 들면 그 값이
	 * 낡는 순간부터 조용히 틀린 수가 쌓이고, 쌓인 뒤에는 어느 시점 환율이었는지 알 수 없다.
	 */
	public Long costMicroKrw(String modelId, Integer inputTokens, Integer outputTokens) {
		if (modelId == null || inputTokens == null || outputTokens == null) {
			return null;
		}
		ModelPrice price = this.pricing.get(modelId);
		return (price != null) ? price.costMicroKrw(inputTokens, outputTokens) : null;
	}

	/**
	 * 모델 하나의 KRW 단가 (#311).
	 *
	 * <p><b>입력과 출력을 따로 받는다.</b> 벤더가 그렇게 고시하며, 하나로 합치면 그 합치는 비율을
	 * 누군가 가정해야 한다.
	 *
	 * <p><b>기본값이 없다</b> (§7.3). 둘 중 하나라도 없으면 이 모델의 단가를 <b>모르는 것</b>이며,
	 * 아는 척한 절반짜리 값보다 {@code null} 이 낫다.
	 *
	 * <p>단위가 <b>100만 토큰당 원</b>인 것은 벤더 고시 형태를 그대로 옮기기 위해서다. 이 단위를
	 * 고르면 마이크로 환산이 정확히 <b>토큰 수 × 단가</b> 가 된다 —
	 * {@code (tokens / 1e6) × KRW × 1e6}.
	 *
	 * @param inputKrwPerMillionTokens  입력 100만 토큰당 원
	 * @param outputKrwPerMillionTokens 출력 100만 토큰당 원
	 */
	public record ModelPrice(BigDecimal inputKrwPerMillionTokens, BigDecimal outputKrwPerMillionTokens) {

		/**
		 * <b>정수로 떨어뜨린다.</b> 부동소수로 돈을 세지 않는다는 것이 마이크로 단위를 쓰는
		 * 이유이며, 그 규칙은 계산 중간에도 유효하다.
		 */
		private Long costMicroKrw(int inputTokens, int outputTokens) {
			if (this.inputKrwPerMillionTokens == null || this.outputKrwPerMillionTokens == null) {
				return null;
			}
			BigDecimal micro = this.inputKrwPerMillionTokens.multiply(BigDecimal.valueOf(inputTokens))
					.add(this.outputKrwPerMillionTokens.multiply(BigDecimal.valueOf(outputTokens)));
			return micro.setScale(0, RoundingMode.HALF_UP).longValueExact();
		}
	}
}
