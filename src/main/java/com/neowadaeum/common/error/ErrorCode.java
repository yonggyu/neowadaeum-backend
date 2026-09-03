package com.neowadaeum.common.error;

import org.springframework.http.HttpStatus;

/**
 * §11 에러 코드 카탈로그. 서버가 내보내는 모든 에러 응답의 {@code error} 값은 이 enum 의 이름이다.
 *
 * <p>클라이언트는 이 코드로 문구를 매핑한다. 서버 {@code message} 를 UI 에 그대로 쓴다고 가정하지 않는다(§9.1).
 * 따라서 여기 담긴 메시지는 <b>안전한 폴백 문구</b>이며, 내부 사정(예외 클래스·SQL·경로·차단 사유)을 담지 않는다(S-6).
 *
 * <p>429 세 종류({@link #RETRY_COOLDOWN} / {@link #RATE_LIMITED} / {@link #QUOTA_EXCEEDED})는 HTTP 상태가
 * 같아도 클라이언트 처리가 다르다. <b>하나로 합치지 않는다</b>(§11).
 */
public enum ErrorCode {

	// ── 400 ──────────────────────────────────────────────────
	/** {@code birthDate} 또는 {@code consents[]} 누락 (§4.1). */
	CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "가입을 계속하려면 추가 정보가 필요해요."),
	/** [I-1] {@code choiceId} 가 직전 턴의 선택지에 없거나 {@code disabled} 상태다 (§4.3-2). */
	INVALID_CHOICE(HttpStatus.BAD_REQUEST, "선택할 수 없는 항목이에요. 화면을 새로 불러와 주세요."),
	/** 일반 입력 검증 실패. 어떤 필드가 왜 틀렸는지는 {@code details.fields} 로 전달한다. */
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),

	// ── 401 / 403 ────────────────────────────────────────────
	/** 토큰 없음 또는 만료. */
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
	/** [R10.2] 만 15세 미만. 계정을 생성하지 않는다 (§4.1). */
	AGE_RESTRICTED(HttpStatus.FORBIDDEN, "만 15세부터 이용할 수 있어요."),
	/**
	 * [R8.12] 계정당 작품 개수 상한 (B-60).
	 *
	 * <p><b>{@code QUOTA_EXCEEDED}(429) 가 아니다.</b> 그 문구는 "오늘 이용할 수 있는 양"이고,
	 * 작품 개수는 <b>날이 바뀌어도 늘지 않는다</b> — 기다리라고 안내하면 기다린 만큼 헛되다.
	 */
	STORY_LIMIT_REACHED(HttpStatus.FORBIDDEN, "만들 수 있는 작품 수를 모두 사용했어요."),
	/** 소유자가 아니거나 권한이 없다. */
	FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 수행할 권한이 없어요."),

	// ── 404 ──────────────────────────────────────────────────
	/** 리소스 없음. 존재 여부 자체가 정보이므로 어떤 리소스인지 밝히지 않는다. */
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 정보를 찾을 수 없어요."),

	// ── 409 ──────────────────────────────────────────────────
	/** [I-6] {@code turnNo} 불일치. 낙관적 잠금 실패 (§4.3-2). */
	TURN_CONFLICT(HttpStatus.CONFLICT, "진행 상태가 최신이 아니에요. 현재 턴으로 맞춰 주세요."),
	/** [R6.5] 계정당 동시 생성 1개를 초과했다 (§13-14c). */
	CONCURRENT_GENERATION(HttpStatus.CONFLICT, "이야기를 만들고 있어요. 잠시만 기다려 주세요."),
	/** [§13-9] 작품당 active 세션은 1개다. */
	SESSION_ALREADY_ACTIVE(HttpStatus.CONFLICT, "이미 진행 중인 이야기가 있어요."),

	/**
	 * 같은 것이 이미 있다 (B-49).
	 *
	 * <p><b>운영자에게만 나간다.</b> 유일 제약 위반이 500 으로 나가면 등록한 사람은 실패의
	 * 이유를 모른 채 다시 시도한다.
	 */
	ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 등록되어 있어요."),

	/**
	 * [R8.6] 검수를 기다리는 중이 아닌 작품에 판정이 왔다 (B-55).
	 *
	 * <p><b>운영자에게만 나간다.</b> 두 검수자가 같은 작품을 각자 열어 두면 나중에 누른 쪽이
	 * 이긴다 — 그것은 판정이 아니라 경합이고, 진 쪽은 자기 판정이 사라진 줄도 모른다.
	 */
	REVIEW_NOT_PENDING(HttpStatus.CONFLICT, "이미 처리된 검수예요."),

	/**
	 * [R8.9] 내려가지 않은 작품에 재검토 요청이 왔다 (#290, §13-59).
	 *
	 * <p><b>{@code VALIDATION_ERROR}(400) 가 아니다.</b> 요청은 형태가 맞고, 맞지 않는 것은
	 * <b>작품이 지금 놓인 자리</b>다 — 그리고 그 자리는 서버가 바꾼다. 400 으로 답하면
	 * 클라이언트는 자기가 보낸 값을 고치려 든다.
	 */
	STORY_NOT_SUSPENDED(HttpStatus.CONFLICT, "지금은 재검토를 요청할 수 없어요."),

	// ── 422 / 423 ────────────────────────────────────────────
	/**
	 * [I-2, R9.5, R9.6] Safety L2 차단 (§4.8).
	 *
	 * <p>문구는 §4.8 에 고정된 값이다. <b>차단 사유를 구체적으로 노출하지 않는다</b> — 어떤 표현이 걸렸는지
	 * 알려주면 우회를 학습시킨다. {@code retry} 액션도 넣지 않는다(동일 입력 → 동일 결과 → 무한 루프).
	 */
	SAFETY_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "이 방향으로는 이야기를 이어갈 수 없어요."),
	/** [R8.10] 작품이 정지되어 새 턴을 만들 수 없다. 기존 기록 열람은 허용된다 (§4.10). */
	STORY_SUSPENDED(HttpStatus.LOCKED, "지금은 이 작품을 이어갈 수 없어요."),

	// ── 429 (세 종류를 합치지 않는다) ────────────────────────
	/** [R6.5] 연속 실패 3회. {@code details.retryAfterSeconds} 를 함께 준다. */
	RETRY_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해 주세요."),
	/** 분당 호출 한도 초과 (§12 B-38). */
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 빨라요. 잠시 후 다시 시도해 주세요."),
	/** 일일 토큰·생성 한도 초과 (§12 B-38). */
	QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 이용할 수 있는 양을 모두 사용했어요."),

	// ── 5xx ──────────────────────────────────────────────────
	/**
	 * [§4.4] 컨텍스트 토큰 예산 초과. 내부 결함이다.
	 *
	 * <p>조용히 잘라내고 진행하지 않는다. 실패시키고 알람을 띄운다.
	 */
	CONTEXT_BUDGET_EXCEEDED(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 문제가 발생했어요."),
	/**
	 * 예상하지 못한 예외의 폴백 (B-03 신설, §11).
	 *
	 * <p>폴백이 없으면 처리되지 않은 예외가 Spring 기본 에러 본문(예외 메시지·경로 포함)으로 나가 S-6 을 위반한다.
	 * {@link #CONTEXT_BUDGET_EXCEEDED} 는 원인이 특정된 내부 결함이므로 폴백으로 재사용하지 않는다.
	 *
	 * <p><b>의도적으로 던지지 않는다.</b> 이 코드가 나가는 순간 서버 결함이며, {@code ERROR} 로그와 알람으로 추적한다.
	 */
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 문제가 발생했어요."),
	/** [R3.3, R5.8] Provider 실패 또는 파싱 2회 실패 (§4.3-6). */
	PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "이야기를 만들지 못했어요. 잠시 후 다시 시도해 주세요."),
	/** [R6.4] Provider 호출 25초 초과 (§4.3-5). 세션 상태는 변하지 않는다. */
	GENERATION_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "이야기를 만드는 데 시간이 너무 오래 걸렸어요.");

	private final HttpStatus status;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	/** 응답 본문의 {@code error} 값. enum 이름이 곧 계약이다. */
	public String code() {
		return name();
	}

	public HttpStatus status() {
		return status;
	}

	/** 응답 본문의 {@code message} 값. 내부 사정을 담지 않는 안전한 문구다(S-6). */
	public String defaultMessage() {
		return defaultMessage;
	}
}
