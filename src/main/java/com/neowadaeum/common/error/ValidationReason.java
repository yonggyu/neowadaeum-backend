package com.neowadaeum.common.error;

import java.util.Locale;

/**
 * {@code VALIDATION_ERROR} 의 <b>최상위 {@code details.reason}</b> 값 (§13-96, §13-97).
 *
 * <p><b>{@code details.fields[].reason} 이 아니다.</b> 그쪽은 검증 라이브러리의 기본 메시지가
 * 섞여 오는 진단 값이라 열거할 수 없고(§13-95), 이쪽은 <b>전부 서버가 적은 고정 코드</b>라
 * 화면이 분기해도 되는 값이다.
 *
 * <p><b>왜 열거인가</b> (이슈 #483). 분기해도 되는 값이면 화면은 <b>그 목록을 알아야</b> 하는데,
 * 값이 네 자리에 흩어진 문자열 리터럴이던 동안 둘이 계약에 없었고 아무 검사도 그것을 보지
 * 못했다 — {@code ErrorDetailsExampleContractTests} 는 {@code details} 의 키 경로와 타입만
 * 대조하므로 {@code {reason: string}} 모양이 맞으면 <b>어떤 값이든</b> 통과한다. 정본이 여기
 * 하나로 모여야 {@code OpenApiContractTests} 가 계약의 {@code ValidationReason} 열거와
 * <b>집합으로</b> 대조할 수 있다.
 *
 * <p><b>값은 카테고리 수준이다</b> (S-11). 어느 칸이 어긋났는지 · 어떤 값을 보냈는지 · 내부
 * 상태가 무엇인지 담지 않는다. 새 값을 더하면 계약도 함께 늘어야 하며, 늘지 않으면 빌드가
 * 깨진다.
 */
public enum ValidationReason {

	/** [R8.6] 승인 상태가 아닌 작품의 공개 범위를 바꾸려 했다. */
	STORY_NOT_APPROVED,

	/** [§13-39] {@code unlisted} 가 아닌 자리에서 {@code public} 으로 올리려 했다. */
	PROMOTE_REQUIRES_UNLISTED,

	/** [§13-76] 선언한 이름이 프롬프트 어휘 예산을 넘었다. {@code vocabularyUsagePercent} 가 함께 온다. */
	VOCABULARY_BUDGET_EXCEEDED,

	/** [R2.2] 기본 엔딩 없는 작품을 발행하려 했다. */
	MISSING_DEFAULT_ENDING;

	/**
	 * 응답 본문의 {@code details.reason} 값.
	 *
	 * <p>계약의 표기는 {@code lower_snake_case} 다 — {@link ErrorCode} 처럼 이름을 그대로 쓰지
	 * 않고 내리는 이유가 그것이다. 값을 상수로 따로 적어 두면 이름과 값이 갈라질 수 있다.
	 */
	public String code() {
		return name().toLowerCase(Locale.ROOT);
	}
}
