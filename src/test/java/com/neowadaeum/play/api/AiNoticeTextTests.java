package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.AiNoticeQuery;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #281 — 고지 문구를 가져오는 규칙이 <b>한 곳에 있다</b> (R11.1, §11).
 *
 * <p>이 규칙을 쓰는 화면이 일곱이다. 화면마다 복사하면 그중 하나가 언젠가 빈 문자열로 흡수하고,
 * <b>그 화면만 고지 없이 나간다</b>. 여기서 규칙 자체를 못박는다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AiNoticeTextTests {

	private final AiNoticeQuery notices = mock(AiNoticeQuery.class);

	private final AiNoticeText notice = new AiNoticeText(this.notices);

	@Test
	void R11_1_the_configured_text_is_returned_as_is() {
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-09-01", "AI가 생성한 이야기입니다.")));

		assertThat(this.notice.require("library")).isEqualTo("AI가 생성한 이야기입니다.");
	}

	/**
	 * <b>빈 문자열로 흡수하지 않는다.</b> 흡수하면 고지가 없는 상태가 정상으로 보이고, §11 이
	 * 의무로 규정한 것이 조용히 지켜지지 않는다 (§13-27).
	 */
	@Test
	void R11_1_a_missing_notice_fails_instead_of_returning_blank() {
		given(this.notices.current()).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.notice.require("library"))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.INTERNAL_ERROR);
	}

	/** 문구를 코드가 만들지 않는다 — 설정에 있는 것을 그대로 낸다 (R11.1). */
	@Test
	void R11_1_no_fallback_text_is_invented() {
		given(this.notices.current()).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.notice.require("play")).isInstanceOf(ApiException.class);
	}
}
