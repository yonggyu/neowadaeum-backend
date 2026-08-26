package com.neowadaeum.ai.gateway;

import com.neowadaeum.ai.provider.AiCallFallback;
import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.SummaryRequest;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.ProviderCallFailedException;
import com.neowadaeum.play.port.TurnRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider 장애 시 다음으로 넘긴다 (R3.7, B-23, ADR-0007).
 *
 * <p><b>승계는 "벤더가 죽었다"에만 해당한다.</b> ADR-0007 이 네 갈래를 나눴다.
 *
 * <table border="1">
 * <caption>실패별 처리</caption>
 * <tr><th>실패</th><th>승계</th><th>근거</th></tr>
 * <tr><td>연결 실패 · 5xx</td><td><b>한다</b></td><td>다른 벤더면 답한다</td></tr>
 * <tr><td>인증 실패 · 4xx</td><td>안 한다</td><td><b>우리 설정이 틀렸다.</b> 넘겨도 같은 실수이고 두 벤더에 청구된다</td></tr>
 * <tr><td>스키마 소진</td><td>안 한다</td><td>이미 재요청을 다 썼다. 더하면 <b>한 턴의 비용 상한이 사라진다</b></td></tr>
 * <tr><td>시간 초과</td><td>안 한다</td><td>예산을 다 썼다 (§13-19). 클라이언트는 그 전에 끊는다</td></tr>
 * </table>
 *
 * <p><b>4xx 와 5xx 를 지금은 구분하지 못한다.</b> 어댑터가 둘 다
 * {@link ProviderCallFailedException} 으로 올리기 때문이다 — 그것은 <b>이 PR 이 남기는 한계</b>이며
 * 상수 주석이 아니라 이슈로 올렸다. 구분이 붙기 전까지는 <b>넓게 승계한다</b>: 인증 실패에서
 * 한 번 더 부르는 비용이, 벤더 장애에서 턴이 죽는 것보다 싸다.
 *
 * <p><b>세션의 provider 고정을 건드리지 않는다</b> (ADR-0007, I-4). 이 클래스에는
 * {@code play_session} 을 쓸 수단이 없다 — 발동 사실은 {@code ai_call_log.fallback_from} 에만
 * 남는다.
 */
public class FallbackChain implements StoryProvider {

	private static final Logger log = LoggerFactory.getLogger(FallbackChain.class);

	private final List<StoryProvider> chain;

	/**
	 * @param chain 순서대로 시도한다. <b>첫째가 정상 경로</b>이며 나머지는 그것이 죽었을 때만 쓰인다
	 */
	public FallbackChain(List<StoryProvider> chain) {
		if (chain == null || chain.isEmpty()) {
			throw new IllegalStateException("a fallback chain needs at least one provider");
		}
		this.chain = List.copyOf(chain);
	}

	/** 세션에 고정되는 것은 <b>체인의 첫째</b>다 (I-4). 승계는 그 값을 바꾸지 않는다 (ADR-0007). */
	@Override
	public String providerId() {
		return this.chain.getFirst().providerId();
	}

	/** 능력도 첫째의 것이다 — 재요청 횟수(R3.3)는 정상 경로 기준으로 정해져야 한다. */
	@Override
	public ProviderCapabilities capabilities() {
		return this.chain.getFirst().capabilities();
	}

	/**
	 * 앞에서부터 시도하고, <b>벤더 장애일 때만</b> 다음으로 넘긴다.
	 *
	 * <p>승계가 일어나면 <b>원래 지목됐던 provider</b> 를 기록에 남긴다 (R3.7). 남기지 않으면
	 * <i>"왜 이 턴만 문체가 다른가"</i> 를 사후에 설명할 수 없다.
	 */
	@Override
	public GeneratedTurn generateTurn(TurnRequest request) {
		String intended = providerId();
		ProviderCallFailedException lastFailure = null;

		for (int index = 0; index < this.chain.size(); index++) {
			StoryProvider provider = this.chain.get(index);
			boolean isFallback = index > 0;
			try {
				return isFallback
						? AiCallFallback.within(intended, () -> provider.generateTurn(request))
						: provider.generateTurn(request);
			}
			catch (ProviderCallFailedException ex) {
				// 원문도 예외 본문도 남기지 않는다 (S-3). 남기는 것은 어느 provider 가 죽었는가까지다.
				log.warn("provider {} failed; falling back", provider.providerId());
				lastFailure = ex;
			}
		}

		throw (lastFailure != null) ? lastFailure : new ProviderCallFailedException("no provider answered");
	}

	/**
	 * <b>요약과 아웃라인에는 승계를 걸지 않는다</b> (§0.2).
	 *
	 * <p>둘 다 아직 구현되지 않았고(B-34 · B-52), 없는 기능에 승계를 미리 걸면 <b>그 경로의 실패가
	 * 조용히 두 배로 청구된다.</b> {@code SchemaRetryingStoryProvider} 가 같은 이유로 같은 선택을 했다.
	 */
	@Override
	public String summarize(SummaryRequest request) {
		return this.chain.getFirst().summarize(request);
	}

	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		return this.chain.getFirst().draftOutline(request);
	}
}
