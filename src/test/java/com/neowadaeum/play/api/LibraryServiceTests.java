package com.neowadaeum.play.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class LibraryServiceTests {

	private static final String NOTICE = "이 이야기는 AI가 생성합니다.";

	private final StoryCatalogFacade stories = mock(StoryCatalogFacade.class);

	private final PlaySessionRepository sessions = mock(PlaySessionRepository.class);

	private final AiNoticeQuery notices = mock(AiNoticeQuery.class);

	private final LibraryService service = new LibraryService(this.stories, this.sessions, this.notices);

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

		assertThat(this.service.library(UUID.randomUUID()).noticeText()).isEqualTo(NOTICE);
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

		assertThatThrownBy(() -> this.service.library(UUID.randomUUID()))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).errorCode())
				.isEqualTo(ErrorCode.INTERNAL_ERROR);
	}
}
