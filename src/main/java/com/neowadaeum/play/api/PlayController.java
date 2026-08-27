package com.neowadaeum.play.api;

import com.neowadaeum.common.web.PlayerRefResolver;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플레이 API (§4.2, §4.3).
 *
 * <p><b>Controller 는 요청 검증과 DTO 변환만 한다</b> (web-api 규칙). 파이프라인도 판정도 여기에 없다.
 *
 * <p><b>{@link PlayerRefResolver} 를 필수 인자로 받는다.</b> 구현 빈이 없으면 이 컨트롤러가 만들어지지
 * 않고 <b>부팅이 멈춘다</b> — {@code dev} 프로파일이 아닌 환경에서 인증 없이 플레이 경로가 열리는
 * 것을 막는 마지막 장치다 (ADR-0004, #34).
 */
@RestController
@RequestMapping("/api/v1")
public class PlayController {

	private final PlayerRefResolver playerRefs;
	private final SessionStarter sessionStarter;
	private final PlayTurnService turns;

	public PlayController(PlayerRefResolver playerRefs, SessionStarter sessionStarter, PlayTurnService turns) {
		this.playerRefs = playerRefs;
		this.sessionStarter = sessionStarter;
		this.turns = turns;
	}

	/**
	 * 세션 시작 (§4.2). 턴 1 이 함께 만들어진다.
	 *
	 * <p>작품당 {@code active} 세션은 1개다 — 중복이면 {@code 409 SESSION_ALREADY_ACTIVE} (§13-9).
	 *
	 * <p><b>{@code restart=true} 는 409 대신 기존 것을 버린다</b> (§13-9, B-17). 버린다는 것은
	 * 지운다는 뜻이 아니다 — 상태만 {@code abandoned} 로 바뀌고 턴·스냅샷·요약은 남는다.
	 */
	@PostMapping("/stories/{storyId}/sessions")
	public ResponseEntity<StartSessionResponse> startSession(@PathVariable UUID storyId,
			@RequestParam(defaultValue = "false") boolean restart) {
		SessionStarter.StartedSession started = this.sessionStarter.start(this.playerRefs.currentPlayerRef(),
				storyId, restart);

		TurnView firstTurn = this.turns.view(storyVersionOf(started), started.firstTurn());

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new StartSessionResponse(started.sessionId(), firstTurn));
	}

	/**
	 * 다음 턴 (§4.3).
	 *
	 * <p>요청 {@code turnNo} 는 <b>지금 화면에 떠 있는 턴</b>이고 응답은 그 +1 이다. 불일치는
	 * {@code 409 TURN_CONFLICT} 이며 현재 턴 상태가 함께 온다 (R6.1).
	 *
	 * <p>{@code Idempotency-Key} 헤더를 지원한다 (R6.2). 와이어프레임의 "다시 시도"는 같은
	 * {@code choiceId} 재전송이므로, 그대로 두면 Provider 가 두 번 불리고 <b>두 번 청구된다</b>.
	 */
	@PostMapping("/sessions/{sessionId}/turns")
	public TurnView advance(@PathVariable UUID sessionId, @Valid @RequestBody TurnRequestBody request,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		return this.turns.advance(this.playerRefs.currentPlayerRef(), sessionId,
				request.withIdempotencyKey(idempotencyKey));
	}

	/**
	 * 세션 삭제 (§13.4).
	 *
	 * <p><b>soft delete 다</b> — 기록은 남고 {@code deleted_at} 이 채워진다. 실제 파기는 보관
	 * 주기를 지키는 배치의 몫이다 (R12.4, B-61).
	 *
	 * <p>이미 지운 세션을 다시 지워도 {@code 204} 다. 삭제는 <b>상태를 맞추는 요청</b>이며,
	 * 두 번째 호출이 실패하면 클라이언트가 재시도할 방법이 없다.
	 */
	@DeleteMapping("/sessions/{sessionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSession(@PathVariable UUID sessionId) {
		this.sessionStarter.delete(this.playerRefs.currentPlayerRef(), sessionId);
	}

	private UUID storyVersionOf(SessionStarter.StartedSession started) {
		return this.sessionStarter.storyVersionIdOf(started.sessionId());
	}

	/**
	 * 세션 시작 응답 (§4.2).
	 *
	 * @param sessionId 이후 턴 요청의 대상
	 * @param turn      턴 1. 시작과 동시에 만들어지므로 별도 요청이 필요 없다
	 */
	public record StartSessionResponse(UUID sessionId, TurnView turn) {
	}
}
