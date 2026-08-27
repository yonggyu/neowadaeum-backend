package com.neowadaeum.common.spi;

import java.util.UUID;

/**
 * AI 사전 고지를 보여 준 사실을 남긴다 (R11.3, §11).
 *
 * <p>§11 은 이것을 <b>입증 책임 대비</b>로 요구한다. 보여 줬다는 주장과 보여 준 기록은 다르다.
 *
 * <p><b>{@code playerRef} 만 받는다</b> (I-3). 회원을 특정하는 것은 {@code identity} 의 일이며,
 * 화면을 가진 모듈은 그 값을 알 필요도 알 방법도 없다.
 *
 * <p><b>구현은 실패로 호출자를 막지 않는다.</b> 고지 이력을 남기지 못했다고 플레이가 멈추면
 * 관측을 붙인 대가로 서비스가 멈춘다 — {@code AiCallRecorder}(B-25)와 같은 판단이다.
 * 다만 <b>조용히 넘어가지도 않는다</b>: 남기지 못한 사실은 로그에 남는다.
 */
public interface AiNoticeRecorder {

	/**
	 * 같은 판본을 이미 보여 줬다면 아무것도 하지 않는다.
	 *
	 * <p>이력은 <b>"보여 줬는가"</b>이지 "몇 번 보여 줬는가"가 아니다. 매번 남기면 플레이 이력이
	 * 되고, 그러면 정작 필요한 질문(이 회원이 이 판본을 봤는가)이 느려진다.
	 */
	void recordExposure(UUID playerRef, NoticeSurface surface);
}
