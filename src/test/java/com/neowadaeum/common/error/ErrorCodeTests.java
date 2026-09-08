package com.neowadaeum.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * §11 에러 코드 카탈로그를 실행 가능한 계약으로 고정한다.
 *
 * <p>여기 적힌 표는 CLAUDE.md §11 의 사본이다. 문서가 바뀌면 이 테스트가 먼저 깨져야 한다.
 */
class ErrorCodeTests {

	/** CLAUDE.md §11 표 전체. */
	private static Map<String, HttpStatus> catalog() {
		Map<String, HttpStatus> catalog = new LinkedHashMap<>();
		catalog.put("CONSENT_REQUIRED", HttpStatus.BAD_REQUEST);
		catalog.put("INVALID_CHOICE", HttpStatus.BAD_REQUEST);
		catalog.put("VALIDATION_ERROR", HttpStatus.BAD_REQUEST);
		catalog.put("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED);
		// §13-87 (#424) — 401 이지만 클라이언트가 할 일이 다르다. 재발급으로 회복되지 않고
		// **로그인 왕복을 처음부터 다시** 해야 한다.
		catalog.put("LOGIN_NONCE_INVALID", HttpStatus.UNAUTHORIZED);
		catalog.put("AGE_RESTRICTED", HttpStatus.FORBIDDEN);
		catalog.put("STORY_LIMIT_REACHED", HttpStatus.FORBIDDEN);
		// §13-91 (#450) — 원고 개수 상한. 작품 수 상한과 성질이 같으므로 같은 403 이며,
		// **`ALREADY_EXISTS`(409) 가 아니다** — 그 문구는 상한에 닿은 사람에게 틀린 말이다.
		catalog.put("DRAFT_LIMIT_REACHED", HttpStatus.FORBIDDEN);
		catalog.put("FORBIDDEN", HttpStatus.FORBIDDEN);
		catalog.put("NOT_FOUND", HttpStatus.NOT_FOUND);
		catalog.put("TURN_CONFLICT", HttpStatus.CONFLICT);
		catalog.put("CONCURRENT_GENERATION", HttpStatus.CONFLICT);
		catalog.put("SESSION_ALREADY_ACTIVE", HttpStatus.CONFLICT);
		// B-49 — 운영자에게만 나간다. 유일 제약 위반이 500 으로 나가면 등록한 사람은 실패의
		// 이유를 모른 채 다시 시도한다.
		catalog.put("ALREADY_EXISTS", HttpStatus.CONFLICT);
		catalog.put("REVIEW_NOT_PENDING", HttpStatus.CONFLICT);
		// #290 · §13-59 — 요청의 형태가 아니라 **작품이 놓인 자리**가 맞지 않는다. 400 으로
		// 답하면 클라이언트는 자기가 보낸 값을 고치려 든다.
		catalog.put("STORY_NOT_SUSPENDED", HttpStatus.CONFLICT);
		catalog.put("SAFETY_BLOCKED", HttpStatus.UNPROCESSABLE_CONTENT);
		catalog.put("STORY_SUSPENDED", HttpStatus.LOCKED);
		catalog.put("RETRY_COOLDOWN", HttpStatus.TOO_MANY_REQUESTS);
		catalog.put("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS);
		catalog.put("QUOTA_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS);
		catalog.put("CONTEXT_BUDGET_EXCEEDED", HttpStatus.INTERNAL_SERVER_ERROR);
		catalog.put("PROVIDER_ERROR", HttpStatus.BAD_GATEWAY);
		catalog.put("GENERATION_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
		catalog.put("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);
		return catalog;
	}

	/** §11 — 카탈로그의 모든 코드가 enum 에 존재한다. 정의되지 않은 코드가 추가돼도 실패한다. */
	@Test
	void S11_enum_matches_the_error_code_catalog_exactly() {
		Map<String, HttpStatus> declared = new LinkedHashMap<>();
		Arrays.stream(ErrorCode.values()).forEach(code -> declared.put(code.code(), code.status()));

		assertThat(declared).containsExactlyInAnyOrderEntriesOf(catalog());
	}

	/** §11 — 429 세 종류는 HTTP 상태가 같아도 클라이언트 처리가 다르다. 하나로 합치지 않는다. */
	@Test
	void S11_three_distinct_codes_share_http_429() {
		assertThat(Arrays.stream(ErrorCode.values())
				.filter(code -> code.status() == HttpStatus.TOO_MANY_REQUESTS)
				.map(ErrorCode::code))
				.containsExactlyInAnyOrder("RETRY_COOLDOWN", "RATE_LIMITED", "QUOTA_EXCEEDED");
	}

	/** §4.8 — 세이프티 차단 문구는 고정값이며, 차단 사유를 드러내지 않는다 (R9.6). */
	@Test
	void R9_6_safety_blocked_message_is_the_fixed_neutral_phrase() {
		assertThat(ErrorCode.SAFETY_BLOCKED.defaultMessage()).isEqualTo("이 방향으로는 이야기를 이어갈 수 없어요.");
	}

	/**
	 * §13-91 — <b>개수 상한 둘은 같은 상태를 쓰고 문구로 갈린다</b> (#450).
	 *
	 * <p>상태를 나누면 화면은 <i>"지금 만들 수 없다"</i> 를 두 갈래로 처리해야 하고, 코드를
	 * 합치면 문구가 하나가 되어 <b>어느 한쪽에는 틀린 말</b>이 된다.
	 */
	@Test
	void S13_91_both_count_limits_are_forbidden_and_differ_only_in_wording() {
		assertThat(ErrorCode.DRAFT_LIMIT_REACHED.status()).isEqualTo(ErrorCode.STORY_LIMIT_REACHED.status());
		assertThat(ErrorCode.DRAFT_LIMIT_REACHED.defaultMessage())
				.isNotEqualTo(ErrorCode.STORY_LIMIT_REACHED.defaultMessage());
	}

	/**
	 * §13-91 — <b>원고 상한의 문구가 상황을 말한다</b> (#450).
	 *
	 * <p>이 이슈의 실질이 문구다. 코드만 못박으면 다음에 코드를 재활용하며 같은 결함이
	 * 다시 난다 — <b>"이미 등록되어 있어요" 는 상한에 닿은 사람에게 하는 말이 아니다.</b>
	 * 상한 값 자체는 담지 않는다 (S-6) — 담으면 정책이 코드와 문구 두 곳에 생긴다.
	 */
	@Test
	void S13_91_draft_limit_message_names_the_limit_not_a_duplicate() {
		String message = ErrorCode.DRAFT_LIMIT_REACHED.defaultMessage();

		assertThat(message).contains("원고").contains("모두 사용");
		assertThat(message).doesNotContain("이미 등록");
		assertThat(message).doesNotContainPattern("\\d");
	}

	/** S-6 — 기본 문구는 사용자에게 보여도 안전해야 한다. 내부 용어가 새지 않는지 확인한다. */
	@Test
	void SEC6_default_messages_do_not_leak_internals() {
		for (ErrorCode code : ErrorCode.values()) {
			assertThat(code.defaultMessage())
					.as("%s", code.code())
					.isNotBlank()
					.doesNotContainIgnoringCase("exception")
					.doesNotContainIgnoringCase("sql")
					.doesNotContainIgnoringCase("jdbc")
					.doesNotContain("com.neowadaeum");
		}
	}
}
