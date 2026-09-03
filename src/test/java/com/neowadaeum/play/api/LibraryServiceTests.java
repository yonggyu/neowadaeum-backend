package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.neowadaeum.catalog.query.GenreView;
import com.neowadaeum.catalog.query.StoryCatalogFacade;
import com.neowadaeum.catalog.query.StoryPage;
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
 * #257 — 라이브러리가 <b>자기 응답에</b> 고지 문구를 싣는다 (R11.1, §11).
 *
 * <p>싣지 않으면 클라이언트가 화면마다 {@code /landing} 을 한 번 더 부르고, 두 응답의 캐시 수명이
 * 갈리면 <b>같은 화면에서 다른 문구</b>가 보인다.
 *
 * <p>#289 — 섹션 단독 조회({@code GET /library/sections/{sectionKey}})도 같은 이유로 자기
 * 응답에 고지 문구를 싣는다. {@link LibraryView#noticeText()} 없이 단독으로 열리는 경로다.
 *
 * <p>§13-54(이슈 #306) — 이 화면은 <b>인증 밖으로 열렸다.</b> 익명 요청에서 개인 필드가 비는
 * 것과, 그때 <b>주인 없는 조회가 나가지 않는 것</b>을 함께 본다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class LibraryServiceTests {

	private static final String NOTICE = "이 이야기는 AI가 생성합니다.";

	private final StoryCatalogFacade stories = mock(StoryCatalogFacade.class);

	private final PlaySessionRepository sessions = mock(PlaySessionRepository.class);

	private final AiNoticeQuery notices = mock(AiNoticeQuery.class);

	private final LibraryService service = new LibraryService(this.stories, this.sessions,
			new AiNoticeText(this.notices));

	@BeforeEach
	void emptyCatalog() {
		given(this.stories.genres()).willReturn(List.of());
		given(this.stories.officialGenreKeys()).willReturn(List.of());
		given(this.stories.cards(any(), any(), any())).willReturn(new StoryPage(List.of(), null));
		given(this.sessions.findByPlayerRefAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(any(), any(),
				any())).willReturn(List.of());
	}

	/** <b>문구가 설정에서 온다</b> — 코드에도 클라이언트에도 상수로 두지 않는다. */
	@Test
	void R11_1_the_library_carries_the_configured_notice_text() {
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-07-21", NOTICE)));

		assertThat(this.service.library(Optional.of(UUID.randomUUID())).noticeText()).isEqualTo(NOTICE);
	}

	/**
	 * <b>문구가 없으면 화면을 내보내지 않는다</b> (§11, R11.1) — 랜딩과 같은 판단이다.
	 *
	 * <p>빈 문자열로 흡수하면 <b>고지가 없는 상태가 정상으로 보인다.</b> 사전 고지는 의무이고,
	 * 설정되지 않은 것은 운영 결함이다.
	 */
	@Test
	void R11_1_a_missing_notice_fails_the_library_instead_of_blanking_it() {
		given(this.notices.current()).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.library(Optional.of(UUID.randomUUID())))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.INTERNAL_ERROR);
	}

	/**
	 * <b>섹션 단독 조회도 자기 응답에 고지 문구를 싣는다</b> (#289).
	 *
	 * <p>이 경로는 {@link LibraryView#noticeText()} 없이 홀로 열린다 — 재시도와 더 보기가
	 * {@code /library} 전체를 다시 부르지 않고 이 엔드포인트만 부르기 때문이다.
	 */
	@Test
	void R289_a_section_fetched_on_its_own_carries_the_configured_notice_text() {
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-07-21", NOTICE)));

		assertThat(this.service.section("recommended", null, null).noticeText()).isEqualTo(NOTICE);
	}

	/**
	 * <b>문구가 없으면 섹션 단독 조회도 내보내지 않는다</b> (§11, R11.1) — 라이브러리 전체와
	 * 같은 판단이다.
	 */
	@Test
	void R289_a_missing_notice_fails_the_standalone_section_instead_of_blanking_it() {
		given(this.notices.current()).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.section("recommended", null, null))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.INTERNAL_ERROR);
	}

	/**
	 * <b>모르는 섹션은 고지 문구보다 먼저 걸린다</b> (I-8 과 같은 순서 원칙 — #257 의
	 * {@code StoryDetailServiceTests} 선례를 따른다).
	 *
	 * <p>순서가 뒤집히면 문구 설정 여부가 <b>섹션의 존재를 알려주는 신호</b>가 된다.
	 */
	@Test
	void R289_an_unknown_section_is_not_found_regardless_of_the_notice() {
		given(this.notices.current()).willReturn(Optional.empty());
		given(this.stories.genres()).willReturn(List.of(new GenreView("romance", "로맨스")));

		assertThatThrownBy(() -> this.service.section("genre:unknown", null, null))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	/**
	 * <b>익명이면 이어하기가 빈 배열이다</b> (§13-54, 이슈 #306).
	 *
	 * <p>주체 없이도 <b>터지지 않는 것</b>이 먼저다 — 인증 밖으로 열린 경로에서 익명은 정상이며,
	 * 예외로 다루면 목록 전체가 401 로 끝난다.
	 */
	@Test
	void S13_54_an_anonymous_library_carries_no_continue_sessions() {
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-07-21", NOTICE)));

		LibraryView view = this.service.library(Optional.empty());

		assertThat(view.continueSessions()).isEmpty();
		assertThat(view.noticeText()).isEqualTo(NOTICE);
	}

	/**
	 * <b>익명이면 세션 조회 자체가 나가지 않는다</b> (§13-54, I-3).
	 *
	 * <p>빈 배열만 단언하면 <b>{@code null} 을 키로 조회하고 결과가 비어서</b> 통과하는 구현도
	 * 함께 통과한다. 주인이 없는 요청에 "진행 중인 세션"이라는 질문은 성립하지 않는다.
	 */
	@Test
	void S13_54_an_anonymous_request_does_not_query_sessions_at_all() {
		given(this.notices.current()).willReturn(Optional.of(new AiNotice("2026-07-21", NOTICE)));

		this.service.library(Optional.empty());

		verify(this.sessions, never())
				.findByPlayerRefAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(any(), any(), any());
	}
}
