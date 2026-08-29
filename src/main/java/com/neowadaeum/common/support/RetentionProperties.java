package com.neowadaeum.common.support;

import java.time.Duration;
import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 무엇을 얼마나 보관하는가 (R12.4, S-10, B-61).
 *
 * <p><b>이 값들은 약관에 적혀 있다.</b> 그래서 바꾸는 것은 코드 결정이 아니라 고지 변경이며,
 * 여기 값과 약관 문구가 어긋나면 <b>지운다고 적어 두고 지우지 않는 상태</b>가 된다 — S-10 이
 * "실제로 구현하고 테스트한다"를 명시한 이유다.
 *
 * <p><b>{@code common} 이 소유한다</b> (§5.4). 프롬프트 로그와 감사 로그는 {@code ai} 가,
 * 세션은 {@code play} 가 지운다 — <b>둘이 같은 표를 봐야 한다.</b>
 *
 * <p><b>기간마다 근거가 다르다.</b> 하나로 묶으면 넷 중 하나를 바꿀 때 나머지가 함께 움직인다.
 *
 * @param promptLogDays 프롬프트·응답 원문 (R12.4). 원문 보관처이므로 가장 짧다
 * @param auditLogYears 감사 로그 (S-10). 사후 추적의 근거이므로 가장 길다
 * @param sessionIdleDays 무활동 세션 (§4.7). 지우는 것이 아니라 <b>만료로 바꾼다</b> — 기록은
 *     남고 이어갈 수만 없게 된다
 * @param previewStoryDays 미리보기가 발행한 작품 (§13-37). <b>원고가 아니라 그 사본이다</b> —
 *     원고({@code story_draft})는 남으므로 다시 보고 싶으면 다시 부르면 된다
 */
@ConfigurationProperties("app.retention")
public record RetentionProperties(Integer promptLogDays, Integer auditLogYears,
		Integer sessionIdleDays, Integer previewStoryDays) {

	public RetentionProperties {
		promptLogDays = (promptLogDays != null) ? promptLogDays : 90;
		auditLogYears = (auditLogYears != null) ? auditLogYears : 3;
		sessionIdleDays = (sessionIdleDays != null) ? sessionIdleDays : 90;
		previewStoryDays = (previewStoryDays != null) ? previewStoryDays : 30;
	}

	public Duration promptLogRetention() {
		return Duration.ofDays(this.promptLogDays);
	}

	/** <b>년은 일로 환산하지 않는다.</b> 윤년이 있고, 3년은 "3년"이라고 적힌 값이다. */
	public Period auditLogRetention() {
		return Period.ofYears(this.auditLogYears);
	}

	public Duration sessionIdleLimit() {
		return Duration.ofDays(this.sessionIdleDays);
	}

	/**
	 * 미리보기 작품 보관 기간 (§13-37).
	 *
	 * <p><b>세션 만료 기간과 같은 값을 쓰지 않는다.</b> 근거가 다르기 때문이다 — 무활동 만료는
	 * <b>사용자가 돌아올 수 있는 기간</b>이고, 이쪽은 <b>아무도 열지 않는 사본이 쌓이는 기간</b>이다.
	 * 하나로 묶으면 둘 중 하나를 바꿀 때 나머지가 함께 움직인다.
	 */
	public Duration previewStoryRetention() {
		return Duration.ofDays(this.previewStoryDays);
	}

	/** 설정을 띄우지 않는 테스트가 쓴다. */
	public static RetentionProperties defaults() {
		return new RetentionProperties(null, null, null, null);
	}
}
