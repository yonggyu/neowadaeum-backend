package com.neowadaeum.identity.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.spi.NoticeSurface;
import com.neowadaeum.identity.domain.AiNoticeImpression;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.AiNoticeImpressionRepository;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * B-14 — 고지 노출 이력의 성질 (R11.3, §11).
 *
 * <p>여기서 보는 것은 <b>언제 남기고 언제 남기지 않는가</b>다. 이력은 "보여 줬는가"이지
 * "몇 번 보여 줬는가"가 아니며, 남기지 못하는 상황이 플레이를 멈추지 않아야 한다.
 *
 * <p>컨테이너가 필요 없다 (ADR-0001).
 */
class AiNoticeExposureRecorderTests {

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	private static final AiNotice NOTICE = new AiNotice("2026-07-21", "이 이야기는 AI가 생성합니다.");

	private final AiNoticeQuery notices = mock(AiNoticeQuery.class);

	private final UserRepository users = mock(UserRepository.class);

	private final AiNoticeImpressionRepository impressions = mock(AiNoticeImpressionRepository.class);

	private final AiNoticeExposureRecorder recorder = new AiNoticeExposureRecorder(this.notices, this.users,
			this.impressions, Clock.fixed(NOW, ZoneOffset.UTC));

	private final UUID playerRef = UUID.randomUUID();

	/** R11.3 — 처음 보여 준 판본은 남는다. 화면 이름도 함께다 (§2.7). */
	@Test
	void R11_3_a_first_exposure_is_recorded_with_its_version_and_surface() {
		givenMember();
		given(this.impressions.existsByUserIdAndNoticeVersion(any(), any())).willReturn(false);

		this.recorder.recordExposure(this.playerRef, NoticeSurface.PLAY);

		ArgumentCaptor<AiNoticeImpression> saved = ArgumentCaptor.forClass(AiNoticeImpression.class);
		verify(this.impressions).save(saved.capture());
		assertThat(saved.getValue().getNoticeVersion()).isEqualTo("2026-07-21");
		assertThat(saved.getValue().getSurface()).isEqualTo(NoticeSurface.PLAY);
		assertThat(saved.getValue().getShownAt()).isEqualTo(NOW);
	}

	/**
	 * <b>같은 판본은 다시 남지 않는다.</b>
	 *
	 * <p>매번 남기면 이력이 플레이 이력이 되고, 정작 필요한 질문(이 회원이 이 판본을 봤는가)이
	 * 느려진다.
	 */
	@Test
	void R11_3_the_same_version_is_not_recorded_twice() {
		givenMember();
		given(this.impressions.existsByUserIdAndNoticeVersion(any(), any())).willReturn(true);

		this.recorder.recordExposure(this.playerRef, NoticeSurface.PLAY);

		verify(this.impressions, never()).save(any());
	}

	/**
	 * <b>문구가 설정되지 않았으면 남길 것이 없다</b> (R11.1).
	 *
	 * <p>기본 문구를 만들어 기록하면 <b>보여 주지 않은 것을 보여 줬다고 남기는 셈</b>이다.
	 */
	@Test
	void R11_1_nothing_is_recorded_when_the_notice_is_not_configured() {
		given(this.notices.current()).willReturn(Optional.empty());

		this.recorder.recordExposure(this.playerRef, NoticeSurface.PLAY);

		verify(this.impressions, never()).save(any());
		verify(this.users, never()).findByPlayerRef(any());
	}

	/** 회원을 찾지 못하면 남기지 않는다 — 주인 없는 이력을 만들지 않는다. */
	@Test
	void I3_an_unknown_player_ref_records_nothing() {
		given(this.notices.current()).willReturn(Optional.of(NOTICE));
		given(this.users.findByPlayerRef(this.playerRef)).willReturn(Optional.empty());

		this.recorder.recordExposure(this.playerRef, NoticeSurface.PLAY);

		verify(this.impressions, never()).save(any());
	}

	/**
	 * <b>실패가 호출자에게 새지 않는다.</b>
	 *
	 * <p>고지 이력을 남기지 못했다고 플레이가 멈추면 <b>관측을 붙인 대가로 서비스가 멈춘다</b>
	 * (B-25 와 같은 판단). 유일 인덱스 위반도 이 경로로 온다 — 이미 남아 있다는 뜻이다.
	 */
	@Test
	void R11_3_a_failure_never_breaks_the_caller() {
		givenMember();
		given(this.impressions.existsByUserIdAndNoticeVersion(any(), any())).willReturn(false);
		given(this.impressions.save(any())).willThrow(new IllegalStateException("identity is down"));

		assertThatCode(() -> this.recorder.recordExposure(this.playerRef, NoticeSurface.PLAY))
				.doesNotThrowAnyException();
	}

	private void givenMember() {
		given(this.notices.current()).willReturn(Optional.of(NOTICE));
		given(this.users.findByPlayerRef(this.playerRef)).willReturn(Optional.of(persisted()));
	}

	/**
	 * 영속화가 채워 줄 {@code id} 를 대신 넣는다.
	 *
	 * <p>프로덕션 코드에 테스트용 세터를 만들지 않기 위해서다 — 세터를 두면 그 경로가 언젠가
	 * 실제 코드에서 쓰이고, 그때 엔티티의 불변성이 조용히 사라진다.
	 */
	private User persisted() {
		User user = User.register(this.playerRef, null, NOW);
		try {
			java.lang.reflect.Field id = User.class.getDeclaredField("id");
			id.setAccessible(true);
			id.set(user, UUID.randomUUID());
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
		return user;
	}
}
