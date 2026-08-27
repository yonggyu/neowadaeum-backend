package com.neowadaeum.identity.notice;

import com.neowadaeum.common.spi.AiNotice;
import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.spi.AiNoticeRecorder;
import com.neowadaeum.common.spi.NoticeSurface;
import com.neowadaeum.identity.domain.AiNoticeImpression;
import com.neowadaeum.identity.domain.User;
import com.neowadaeum.identity.repository.AiNoticeImpressionRepository;
import com.neowadaeum.identity.repository.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 고지 노출 이력을 남긴다 (R11.3, §11) — {@link AiNoticeRecorder} 의 identity 쪽 구현.
 *
 * <p><b>{@code playerRef} → {@code user.id} 변환이 여기서만 일어난다</b> (I-3). 화면을 가진
 * 모듈은 회원을 특정할 값을 알지 못하며, 그 한 겹이 스토어 분리의 실질이다 (§5.3).
 *
 * <p><b>§13-8 — {@code consent_log} 에 쓰지 않는다.</b> 노출은 동의가 아니라 표시 사실이며,
 * 섞으면 동의 이력의 법적 증빙력이 흐려진다.
 *
 * <p><b>실패가 호출자에게 새지 않는다.</b> 고지 이력을 남기지 못했다고 플레이가 멈추면 관측을
 * 붙인 대가로 서비스가 멈춘다 (B-25 와 같은 판단). 대신 <b>조용히 넘어가지도 않는다</b> —
 * 남기지 못한 사실이 로그에 남고, 그 비율은 B-48 의 관측 대상이다.
 */
@Component
public class AiNoticeExposureRecorder implements AiNoticeRecorder {

	private static final Logger log = LoggerFactory.getLogger(AiNoticeExposureRecorder.class);

	private final AiNoticeQuery notices;

	private final UserRepository users;

	private final AiNoticeImpressionRepository impressions;

	private final Clock clock;

	public AiNoticeExposureRecorder(AiNoticeQuery notices, UserRepository users,
			AiNoticeImpressionRepository impressions, Clock clock) {
		this.notices = notices;
		this.users = users;
		this.impressions = impressions;
		this.clock = clock;
	}

	@Override
	public void recordExposure(UUID playerRef, NoticeSurface surface) {
		try {
			record(playerRef, surface);
		}
		catch (RuntimeException ex) {
			// 유일 인덱스 위반도 여기로 온다 — 이미 남아 있다는 뜻이며 정상이다.
			log.warn("ai.notice.impression.skipped surface={} reason={}", surface,
					ex.getClass().getSimpleName());
		}
	}

	/**
	 * <b>트랜잭션으로 감싸지 않는다.</b> 세 호출은 각자 짧은 트랜잭션이며, 확인과 저장 사이의
	 * 경쟁은 <b>DB 의 유일 인덱스</b>가 막는다 (V4). 여기서 {@code @Transactional} 을 쓰면
	 * 같은 클래스 안 호출이라 프록시를 지나지 않고, 프록시를 지나게 만들면 이번엔 안에서 잡은
	 * 예외가 커밋 시점에 되살아난다.
	 */
	private void record(UUID playerRef, NoticeSurface surface) {
		Optional<AiNotice> notice = this.notices.current();
		if (notice.isEmpty()) {
			// 설정되지 않은 상태다. 문구가 없으면 보여 준 것도 없으므로 남길 것도 없다 —
			// 그러나 그 상태가 정상으로 보이면 안 된다 (R11.1).
			log.warn("ai.notice.missing surface={}", surface);
			return;
		}
		String version = notice.get().version();
		Optional<User> user = this.users.findByPlayerRef(playerRef);
		if (user.isEmpty() || this.impressions.existsByUserIdAndNoticeVersion(user.get().getId(), version)) {
			// 이미 본 판본이면 아무것도 하지 않는다. 이력은 "보여 줬는가"이지 횟수가 아니다.
			return;
		}
		this.impressions.save(
				AiNoticeImpression.shown(user.get().getId(), version, surface, this.clock.instant()));
	}
}
