package com.neowadaeum.common.spi;

import java.util.Optional;

/**
 * 현재 고지 문구 조회 (R11.1, B-14).
 *
 * <p><b>파싱을 한 곳에 모은다.</b> 설정값의 모양({@code {"version": ..., "text": ...}})을 읽는
 * 쪽마다 알면 그중 하나가 늦게 바뀐다. 여기 뒤에 있는 구현 하나만 그 모양을 안다.
 *
 * <p><b>없으면 비어 있다 — 기본 문구를 만들지 않는다.</b> 폴백을 두는 순간 R11.1 이 무너진다.
 * 값이 없는 상태는 <b>설정하지 않은 것</b>이며, 그 사실이 드러나야 한다.
 */
public interface AiNoticeQuery {

	Optional<AiNotice> current();
}
