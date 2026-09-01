package com.neowadaeum.play.orchestrator;

import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 턴 응답 <b>이후</b>에 요약을 시작한다 (R4.6, B-34).
 *
 * <p><b>이 클래스가 R4.6 의 구조적 보장이다.</b> "비동기로 한다"는 규칙을 파이프라인 안의 주석으로
 * 두면 언젠가 누가 동기 호출로 바꾸고, 그 사고는 <b>턴이 느려졌다</b>는 형태로만 나타난다 — 원인이
 * 요약이라는 것은 지연 분포를 보기 전까지 모른다. 실행기에 던지는 지점을 따로 두면 <b>기다리는
 * 코드를 쓸 자리가 없다.</b>
 *
 * <p><b>실패를 밖으로 내보내지 않는다.</b> 이 코드가 도는 시점에 사용자는 이미 응답을 받았다.
 * 여기서 예외를 던져 봐야 받을 곳이 없고, 실행기의 기본 처리기에 실려 <b>원문이 로그로 흘러갈</b>
 * 위험만 생긴다 (S-3).
 */
public class AsyncSummaryTrigger {

	private static final Logger log = LoggerFactory.getLogger(AsyncSummaryTrigger.class);

	private final StorySummarizer summarizer;

	private final Executor executor;

	public AsyncSummaryTrigger(StorySummarizer summarizer, Executor executor) {
		this.summarizer = summarizer;
		this.executor = executor;
	}

	/**
	 * 방금 저장된 턴을 기준으로 요약을 갱신한다.
	 *
	 * <p><b>돌아오는 데 걸리는 시간이 사용자 대기 시간이다.</b> 그래서 여기서는 제출만 한다.
	 */
	public void afterTurn(UUID sessionId, int turnNo) {
		try {
			this.executor.execute(() -> compress(sessionId, turnNo));
		}
		catch (RuntimeException ex) {
			// 실행기가 거부해도(종료 중·큐 포화) 턴은 이미 나갔다. 다음 턴이 다시 시도한다.
			log.warn("summary task was not accepted; the next turn will try again (R4.6)");
		}
	}

	private void compress(UUID sessionId, int turnNo) {
		try {
			this.summarizer.compress(sessionId, turnNo);
		}
		catch (RuntimeException ex) {
			// 세션 좌표까지만 남긴다. 본문·요약 원문은 남기지 않는다 (S-3).
			log.warn("summary update failed for session {} at turn {}", sessionId, turnNo);
		}
	}
}
