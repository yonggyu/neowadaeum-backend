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
		catalog.put("AGE_RESTRICTED", HttpStatus.FORBIDDEN);
		catalog.put("STORY_LIMIT_REACHED", HttpStatus.FORBIDDEN);
		catalog.put("FORBIDDEN", HttpStatus.FORBIDDEN);
		catalog.put("NOT_FOUND", HttpStatus.NOT_FOUND);
		catalog.put("TURN_CONFLICT", HttpStatus.CONFLICT);
		catalog.put("CONCURRENT_GENERATION", HttpStatus.CONFLICT);
		catalog.put("SESSION_ALREADY_ACTIVE", HttpStatus.CONFLICT);
		// B-49 — 운영자에게만 나간다. 유일 제약 위반이 500 으로 나가면 등록한 사람은 실패의
		// 이유를 모른 채 다시 시도한다.
		catalog.put("ALREADY_EXISTS", HttpStatus.CONFLICT);
		catalog.put("REVIEW_NOT_PENDING", HttpStatus.CONFLICT);
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
