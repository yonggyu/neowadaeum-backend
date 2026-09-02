package com.neowadaeum.common.spi;

import java.util.Optional;

/**
 * 지금 유효한 약관 판본 조회 (R10.2, 이슈 #261).
 *
 * <p><b>왜 SPI 인가.</b> 값을 소유하는 것은 {@code catalog}({@code service_config}) 이고 읽어야
 * 하는 것은 {@code identity} 다. 둘을 직접 잇는 대신 {@code common} 에 계약을 두고 구현을 데이터
 * 소유 모듈에 둔다 — {@link AiNoticeQuery} 와 같은 형태다 (ADR-0002).
 *
 * <p><b>설정값의 모양은 구현 하나만 안다.</b> 읽는 쪽마다 알면 그중 하나가 늦게 바뀐다.
 *
 * <p><b>없으면 비어 있다 — 기본 판본을 만들지 않는다.</b> {@code "v1"} 같은 폴백을 두는 순간
 * 이슈 #261 이 고치려던 상태(프론트가 판본을 상수로 들고 있는 것)가 서버로 옮겨올 뿐이다. 값이
 * 없는 상태는 <b>설정하지 않은 것</b>이며, 그 사실이 드러나야 한다.
 */
public interface ConsentTermsQuery {

	/**
	 * @param consentType 동의 종류의 계약 표기 — {@code tos} · {@code privacy} · {@code ai_notice}
	 * @return 그 종류의 현재 약관. 설정되지 않았으면 비어 있다
	 */
	Optional<ConsentTerm> find(String consentType);
}
