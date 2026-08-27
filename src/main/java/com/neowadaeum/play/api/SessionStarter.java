package com.neowadaeum.play.api;

import com.neowadaeum.play.port.TurnGenerationPort;
import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.common.spi.AiNoticeRecorder;
import com.neowadaeum.common.spi.NoticeSurface;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.SessionStatus;
import org.springframework.data.domain.Limit;
import com.neowadaeum.play.orchestrator.TurnOutcome;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 세션 시작 (§4.2).
 *
 * <p><b>I-4 — 생성 시점에 고정한다.</b> {@code story.current_version_id} 를 복사해 들고 가므로,
 * 나중에 새 버전이 발행돼도 진행 중 세션은 그대로다 (R2.1, R8.8). provider 와 model 도 같다.
 *
 * <p>턴 1 은 §4.3 과 <b>같은 파이프라인</b>이 만든다 — 단 {@code choiceId} 검증이 없다 (§4.2).
 * 별도 경로를 만들면 두 곳이 서서히 갈라진다.
 */
@Service
public class SessionStarter {

	/**
	 * 슬라이스에는 모델이 없다.
	 *
	 * <p>{@code model_id} 는 NOT NULL 이고 I-4 상 세션에 고정돼야 하는데, 결정론 Provider 는
	 * 모델을 쓰지 않는다. 용도별 모델 설정은 <b>B-24</b> 이며 슬라이스 밖이다 — 그때까지 시나리오
	 * 기반임을 드러내는 값을 넣는다. 빈 문자열이나 {@code "unknown"} 을 넣으면 나중에 그 값이
	 * 무슨 뜻이었는지 알 수 없다.
	 */
	private static final String SLICE_MODEL_ID = "scenario";

	/** 작품당 {@code active} 는 1개다 (§13-9). 목록으로 받되 한 건만 읽는다. */
	private static final Limit ONE = Limit.of(1);

	private final PlaySessionRepository sessions;
	private final StoryVersionFacade storyVersions;
	private final TurnPipeline pipeline;
	private final TurnGenerationPort provider;
	private final AiNoticeRecorder aiNotices;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public SessionStarter(PlaySessionRepository sessions, StoryVersionFacade storyVersions,
			TurnPipeline pipeline, TurnGenerationPort provider, AiNoticeRecorder aiNotices, Clock clock,
			PlatformTransactionManager playTransactionManager) {
		this.transactions = new TransactionTemplate(playTransactionManager);
		this.sessions = sessions;
		this.storyVersions = storyVersions;
		this.pipeline = pipeline;
		this.provider = provider;
		this.aiNotices = aiNotices;
		this.clock = clock;
	}

	/**
	 * 새 세션을 만들고 첫 턴을 생성한다.
	 *
	 * <p><b>파이프라인 호출을 트랜잭션 밖에 둔다</b> (§9.2, §13-14-a). 세션 저장까지만 짧게 커밋하고,
	 * 턴 생성은 그 뒤에 일어난다 — 안에서 부르면 Provider 호출이 트랜잭션에 들어온다.
	 */
	public StartedSession start(UUID playerRef, UUID storyId, boolean restart) {
		UUID sessionId = this.transactions.execute(status -> createSession(playerRef, storyId, restart));

		// R11.3 — 플레이를 시작하는 순간이 사전 고지의 자리다 (§4.1 의 "사전"). 매 턴이 아니라
		// 여기서 한 번 남긴다. 기록기는 실패해도 플레이를 막지 않는다.
		this.aiNotices.recordExposure(playerRef, NoticeSurface.PLAY);

		TurnOutcome first = this.pipeline.advance(sessionId, null);
		return new StartedSession(sessionId, first);
	}

	/**
	 * 짧은 TX.
	 *
	 * <p>{@code @Transactional} 을 쓰지 않는 이유는 <b>자기 호출에서는 프록시가 걸리지 않기</b>
	 * 때문이다 — 애노테이션만 보고 트랜잭션이 있다고 믿게 되는 자리다. {@link TransactionTemplate}
	 * 은 경계가 코드에 드러나고, {@code TurnPipeline} 과도 같은 방식이다.
	 */
	private UUID createSession(UUID playerRef, UUID storyId, boolean restart) {
		Instant now = Instant.now(this.clock);

		// §13-9 — 작품당 active 세션 1개. DB 의 partial unique index 가 마지막 방어선이고,
		// 이 확인은 사용자에게 409 를 돌려주기 위한 것이다.
		//
		// restart=true 면 409 대신 기존 것을 버린다. **지우지 않는다** — 지나간 플레이는
		// 기록이며 그 위에 턴·스냅샷·요약이 매달려 있다.
		this.sessions.findByPlayerRefAndStoryIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(
						playerRef, storyId, SessionStatus.ACTIVE, ONE)
				.forEach(existing -> {
					if (!restart) {
						throw new ApiException(ErrorCode.SESSION_ALREADY_ACTIVE);
					}
					existing.abandon(now);
				});

		UUID storyVersionId = this.storyVersions.findCurrentVersionId(storyId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

		// 버린 세션을 먼저 내보낸다. 같은 트랜잭션 안이지만 partial unique index 는 flush 시점에
		// 보므로, 순서를 맡겨 두면 새 세션 INSERT 가 먼저 나가 제약에 걸린다.
		this.sessions.flush();

		return this.sessions.save(PlaySession.start(playerRef, storyId, storyVersionId,
				this.provider.providerId(), SLICE_MODEL_ID, false, now)).getId();
	}

	/**
	 * 세션을 지운다 (§13.4, §4.7).
	 *
	 * <p><b>남의 세션은 없는 것과 구분되지 않는다</b> (I-3). 존재 여부가 새면 세션 id 를 훑어
	 * 다른 사람이 무엇을 하고 있는지 알 수 있다.
	 *
	 * <p>이미 지운 세션에도 성공으로 답한다 — 삭제는 상태를 맞추는 요청이고, 두 번째 호출이
	 * 실패하면 클라이언트가 재시도할 방법이 없다.
	 */
	public void delete(UUID playerRef, UUID sessionId) {
		this.transactions.executeWithoutResult(status -> {
			PlaySession session = this.sessions.findById(sessionId)
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			if (!session.getPlayerRef().equals(playerRef)) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			if (session.getDeletedAt() == null) {
				session.deleteBy(Instant.now(this.clock));
			}
		});
	}

	/** 응답 조립이 작품 버전을 필요로 한다 — 세션이 들고 있는 값을 그대로 준다 (I-4). */
	public UUID storyVersionIdOf(UUID sessionId) {
		return this.sessions.findById(sessionId)
				.orElseThrow(() -> new IllegalStateException("방금 만든 세션을 찾지 못했다: " + sessionId))
				.getStoryVersionId();
	}

	/** 생성된 세션과 그 첫 턴. */
	public record StartedSession(UUID sessionId, TurnOutcome firstTurn) {
	}
}
