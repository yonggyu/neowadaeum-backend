package com.neowadaeum.play.preview;

import com.neowadaeum.common.spi.TestSessionStarter;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.orchestrator.TurnOutcome;
import com.neowadaeum.play.orchestrator.TurnPipeline;
import com.neowadaeum.play.port.TurnGenerationPort;
import com.neowadaeum.play.repository.PlaySessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미리보기 세션을 연다 (R8.13, B-53).
 *
 * <p><b>§4.3 의 같은 파이프라인을 탄다.</b> 미리보기 전용 생성 경로를 만들면 두 곳이 갈라지고,
 * 그러면 <b>작성자가 미리 본 것이 독자가 겪는 것과 달라진다</b> — 미리보기의 목적이 사라진다.
 *
 * <p><b>{@code is_test_session = true} 이고 턴 상한이 있다</b> (I-18, R8.13). 상한을
 * 클라이언트에 맡기면 그것은 상한이 아니라 안내다.
 *
 * <p><b>"작품당 active 1개"(§13-9)를 적용하지 않는다.</b> 미리보기 작품은 매번 새로 발행되므로
 * 같은 작품이 둘일 수 없다 — 그 인덱스가 막을 상황이 애초에 없다.
 */
@Service
public class PreviewSessionStarter implements TestSessionStarter {

	private static final String PREVIEW_MODEL_ID = "preview";

	private final PlaySessionRepository sessions;

	private final TurnPipeline pipeline;

	private final TurnGenerationPort provider;

	private final Clock clock;

	private final TransactionTemplate transactions;

	public PreviewSessionStarter(PlaySessionRepository sessions, TurnPipeline pipeline,
			TurnGenerationPort provider, Clock clock,
			PlatformTransactionManager playTransactionManager) {
		this.sessions = sessions;
		this.pipeline = pipeline;
		this.provider = provider;
		this.clock = clock;
		this.transactions = new TransactionTemplate(playTransactionManager);
	}

	/**
	 * <b>파이프라인 호출을 트랜잭션 밖에 둔다</b> (§9.2) — {@code SessionStarter} 와 같은 이유다.
	 */
	@Override
	public TestSession start(UUID playerRef, UUID storyId, UUID storyVersionId, int turnLimit) {
		UUID sessionId = this.transactions.execute(status -> this.sessions
				.save(PlaySession.startLimited(playerRef, storyId, storyVersionId,
						this.provider.providerId(), PREVIEW_MODEL_ID, turnLimit,
						Instant.now(this.clock)))
				.getId());

		TurnOutcome first = this.pipeline.advance(sessionId, null);
		return new TestSession(sessionId, first.turnNo());
	}
}
