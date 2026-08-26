package com.neowadaeum.ai.gateway;

import com.neowadaeum.ai.provider.OutlineRequest;
import com.neowadaeum.ai.provider.OutlineResult;
import com.neowadaeum.ai.provider.ProviderCapabilities;
import com.neowadaeum.ai.provider.StoryProvider;
import com.neowadaeum.ai.provider.SummaryRequest;
import com.neowadaeum.play.port.GeneratedTurn;
import com.neowadaeum.play.port.TurnRequest;

/**
 * Provider 앞단 (§3.3 용어, B-18 골격 · B-19 페이로드 검증).
 *
 * <p><b>게이트웨이가 하는 일</b> — 설정이 지목한 어댑터를 고르고(R3.1), 시간 제한으로 감싸고(R6.4),
 * <b>나가는 페이로드를 화이트리스트로 검증한다</b>(I-3, B-19). fallback 체인(B-23) · 호출 로그(B-25)는
 * 각자의 작업이며, <b>여기에 빈 후크를 미리 만들어 두지 않는다.</b>
 *
 * <p><b>검증이 어댑터 호출 앞에 있는 것이 요점이다.</b> 어댑터마다 스스로 검사하게 하면 새 어댑터가
 * 그것을 잊고, 잊은 사실은 유출이 일어난 뒤에 알게 된다. 통로가 하나면 잊을 자리가 없다.
 *
 * <p><b>왜 {@code StoryProvider} 를 구현하는가.</b> 호출자({@code play})가 보는 것은 §5.4 가 허용한
 * {@code ai :: provider} 하나이고 (ADR-0005), 게이트웨이가 사는 {@code ai.gateway} 는 내부다.
 * 같은 seam 을 구현하면 <b>경계를 바꾸지 않고</b> 앞단을 끼울 수 있다 — 이 작업의 diff 에
 * {@code play} 가 한 줄도 들어가지 않는 이유다.
 *
 * <p><b>{@link #providerId()} 는 게이트웨이가 아니라 선택된 어댑터의 id 를 돌려준다.</b> 세션에
 * 고정되는 값이 그것이기 때문이다 (I-4, R3.5). {@code "gateway"} 를 저장하면 나중에 어느 벤더로
 * 돌았는지 알 수 없다.
 *
 * <p><b>선택은 생성 시점에 한 번 일어난다.</b> R3.1 이 요구하는 것은 "코드 배포 없이"이지 "무중단"이
 * 아니다. 매 호출마다 다시 고르면 한 세션 안에서 provider 가 바뀔 수 있고 그것은 I-4 위반이다.
 */
public class AiGateway implements StoryProvider {

	private final StoryProvider active;

	private final PayloadWhitelistValidator payloadWhitelist;

	/**
	 * @param active           시간 제한까지 적용된 활성 Provider. 조립은 {@link AiGatewayConfiguration} 이 한다
	 * @param payloadWhitelist 나가는 페이로드의 필드 검증기 (I-3)
	 */
	public AiGateway(StoryProvider active, PayloadWhitelistValidator payloadWhitelist) {
		if (active == null) {
			throw new IllegalStateException("the gateway needs an active provider");
		}
		if (payloadWhitelist == null) {
			// 검증기 없이 도는 게이트웨이는 I-3 의 런타임 보장이 없는 게이트웨이다.
			throw new IllegalStateException("the gateway needs a payload whitelist validator");
		}
		this.active = active;
		this.payloadWhitelist = payloadWhitelist;
	}

	@Override
	public String providerId() {
		return this.active.providerId();
	}

	@Override
	public ProviderCapabilities capabilities() {
		return this.active.capabilities();
	}

	@Override
	public GeneratedTurn generateTurn(TurnRequest request) {
		// I-3 — 어댑터에 닿기 전에 막는다. 지우고 보내지 않는다 (B-19).
		this.payloadWhitelist.validate(request);
		return this.active.generateTurn(request);
	}

	@Override
	public String summarize(SummaryRequest request) {
		this.payloadWhitelist.validate(request);
		return this.active.summarize(request);
	}

	@Override
	public OutlineResult draftOutline(OutlineRequest request) {
		this.payloadWhitelist.validate(request);
		return this.active.draftOutline(request);
	}
}
