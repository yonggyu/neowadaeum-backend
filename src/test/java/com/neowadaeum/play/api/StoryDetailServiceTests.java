package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
 * <p>§13-54(이슈 #306) — 이 화면도 <b>인증 밖으로 열렸다.</b> 익명 요청에서 {@code mySession}
 * 만 비고 작품 쪽 판정은 달라지지 않는다.
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
			new AiNoticeText(this.notices));

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

		assertThat(this.service.detail(Optional.of(UUID.randomUUID()), STORY_ID).noticeText())
				.isEqualTo(NOTICE);
	}

	/**
	 * <b>문구가 없으면 화면을 내보내지 않는다</b> (§11, R11.1) — 랜딩과 같은 판단이다.
	 *
	 * <p>빈 문자열로 흡수하면 <b>고지가 없는 상태가 정상으로 보인다.</b>
	 */
	@Test
	void R11_1_a_missing_notice_fails_the_story_detail_instead_of_blanking_it() {
		given(this.notices.current()).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.detail(Optional.of(UUID.randomUUID()), STORY_ID))
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

		assertThatThrownBy(() -> this.service.detail(Optional.of(UUID.randomUUID()), unknown))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	/**
	 * <b>익명이면 {@code mySession} 이 {@code null} 이다</b> (§13-54, 이슈 #306).
	 *
	 * <p>세션이 없는 회원과 <b>같은 모양</b>이다 — 익명 전용 표시를 새로 만들면 클라이언트가
	 * 결국 같은 화면을 그리는 분기를 하나 더 갖는다.
	 */
	@Test
	void S13_54_an_anonymous_detail_carries_no_my_session() {
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-07-21", NOTICE)));

		StoryDetailResponse response = this.service.detail(Optional.empty(), STORY_ID);

		assertThat(response.mySession()).isNull();
		assertThat(response.story().title()).isEqualTo("제목");
	}

	/**
	 * <b>익명이면 세션 조회 자체가 나가지 않는다</b> (§13-54, I-3).
	 *
	 * <p>{@code null} 을 키로 조회하고 결과가 비어서 통과하는 구현을 함께 막는다.
	 */
	@Test
	void S13_54_an_anonymous_request_does_not_query_sessions_at_all() {
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-07-21", NOTICE)));

		this.service.detail(Optional.empty(), STORY_ID);

		verify(this.sessions, never())
				.findByPlayerRefAndStoryIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(any(), any(),
						any(), any());
	}

	/**
	 * <b>볼 수 없는 작품은 익명에게도 {@code NOT_FOUND} 다</b> (I-8, §13-54).
	 *
	 * <p>인증을 걷어내면서 판정이 느슨해지지 않았다는 것을 익명 쪽에서 한 번 더 못박는다 —
	 * {@code 403} 이면 <b>id 하나로 승인 대기 중인 작품의 존재가 확인된다.</b>
	 */
	@Test
	void I8_an_invisible_story_is_not_found_for_an_anonymous_request_too() {
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-07-21", NOTICE)));
		UUID hidden = UUID.randomUUID();
		given(this.stories.detail(hidden)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.detail(Optional.empty(), hidden))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}
}
