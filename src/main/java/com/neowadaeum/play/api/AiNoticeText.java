package com.neowadaeum.play.api;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AiNoticeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 화면에 실을 고지 문구를 가져온다 (R11.1, §11, 이슈 #281).
 *
 * <p><b>규칙은 하나다 — 문구 없이 그 화면을 내보내지 않는다.</b> §11 이 사전 고지를 의무로
 * 규정하고 R11.1 이 하드코딩을 금지하므로, 둘을 함께 지키는 방법이 그것뿐이다 (§13-27).
 * 빈 문자열로 흡수하면 <b>고지가 없는 상태가 정상으로 보인다.</b>
 *
 * <p><b>왜 한 곳에 모으는가.</b> 이 규칙을 쓰는 화면이 일곱이 됐다 (#257 이 셋으로, #281 이
 * 일곱으로 늘렸다). 판정을 화면마다 복사하면 <b>그중 하나가 언젠가 빈 문자열로 흡수하고</b>,
 * 그 화면만 고지 없이 나간다. 모양이 같아서 합치는 것이 아니라 <b>의미가 같아서</b> 합친다.
 *
 * <p><b>호출 시점은 부르는 쪽이 정한다.</b> 이 클래스는 응답 조립을 감싸지 않는다 — 작품
 * 상세는 {@code NOT_FOUND} 판정이 문구 조회보다 <b>먼저</b>여야 하고(I-8), 순서를 여기서
 * 정해 버리면 그 규칙이 감춰진다.
 */
@Component
public class AiNoticeText {

	private static final Logger log = LoggerFactory.getLogger(AiNoticeText.class);

	private final AiNoticeQuery notices;

	public AiNoticeText(AiNoticeQuery notices) {
		this.notices = notices;
	}

	/**
	 * @param surface 어느 화면이 요구했는가. <b>로그에만 쓴다</b> — 설정이 비어 있을 때 어느
	 *                경로가 막혔는지가 드러나야 한다
	 * @return 화면에 그대로 나가는 문구
	 * @throws ApiException {@code INTERNAL_ERROR} — 문구가 설정되지 않았다. <b>운영 결함이며
	 *     조용히 넘어가서는 안 된다</b> (R11.1)
	 */
	public String require(String surface) {
		return this.notices.current().orElseThrow(() -> {
			// 문구 자체는 싣지 않는다 — 키와 이유만 남긴다.
			log.error("ai.notice.missing surface={} — 고지 문구 없이 화면을 내보내지 않는다 (R11.1)", surface);
			return new ApiException(ErrorCode.INTERNAL_ERROR);
		}).text();
	}
}
