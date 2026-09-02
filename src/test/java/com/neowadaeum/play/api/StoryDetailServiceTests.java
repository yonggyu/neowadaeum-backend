package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.catalog.query.StoryDetailView;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #257 — 작품 상세가 <b>자기 응답에</b> 고지 문구를 싣는다 (R11.1, §11).
 *
 * <p>라이브러리와 같은 이유다 — Footer 가 문구를 상시 표시하므로, 주지 않으면 클라이언트가
 * 화면마다 {@code /landing} 을 한 번 더 부른다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class StoryDetailServiceTests {

	private static final UUID STORY_ID = UUID.fromString("11111111-1111-4111-8111-000000000001");

	private static final String NOTICE = "이 이야기는 AI가 생성합니다.";

	private final StoryCatalogFacade stories = mock(StoryCatalogFacade.class);

	private final PlaySessionRepository sessions = mock(PlaySessionRepository.class);

	private final AiNoticeQuery notices = mock(AiNoticeQuery.class);

	private final StoryDetailService service = new StoryDetailService(this.stories, this.sessions,
			this.notices);

	@BeforeEach
	void visibleStoryWithoutSession() {
		given(this.stories.detail(STORY_ID)).willReturn(Optional.of(new StoryDetailView(STORY_ID, "제목",
				null, List.of("romance"), "설명", "세계관", "official", null, 3, 4, List.of())));
		given(this.sessions.findByPlayerRefAndStoryIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(any(),
				any(), any(), any())).willReturn(List.of());
	}

	/** <b>문구가 설정에서 온다</b> — 코드에도 클라이언트에도 상수로 두지 않는다. */
	@Test
	void R11_1_the_story_detail_carries_the_configured_notice_text() {
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-07-21", NOTICE)));

		assertThat(this.service.detail(UUID.randomUUID(), STORY_ID).noticeText()).isEqualTo(NOTICE);
	}

	/**
	 * <b>문구가 없으면 화면을 내보내지 않는다</b> (§11, R11.1) — 랜딩과 같은 판단이다.
	 *
	 * <p>빈 문자열로 흡수하면 <b>고지가 없는 상태가 정상으로 보인다.</b>
	 */
	@Test
	void R11_1_a_missing_notice_fails_the_story_detail_instead_of_blanking_it() {
		given(this.notices.current()).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.detail(UUID.randomUUID(), STORY_ID))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.INTERNAL_ERROR);
	}

	/**
	 * <b>없는 작품은 고지 문구보다 먼저 걸린다</b> (I-8).
	 *
	 * <p>순서가 뒤집히면 문구 설정 여부가 <b>작품의 존재를 알려주는 신호</b>가 된다 — 볼 수 없는
	 * 작품과 없는 작품이 구분되지 않아야 한다.
	 */
	@Test
	void I8_an_invisible_story_is_not_found_regardless_of_the_notice() {
		given(this.notices.current()).willReturn(Optional.empty());
		UUID unknown = UUID.randomUUID();
		given(this.stories.detail(unknown)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.detail(UUID.randomUUID(), unknown))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}
}
