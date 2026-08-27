package com.neowadaeum.identity.api;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.auth.SignupInfo;
import com.neowadaeum.identity.domain.ConsentType;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;

/**
 * 동의 한 건 (§13-22 의 {@code ConsentItem}).
 *
 * <p>계약의 {@code consentType} 은 소문자 enum 이다. <b>목록에 없는 값을 기본값으로 흡수하지
 * 않는다</b> — 흡수하면 오타가 "동의하지 않음"으로 조용히 바뀐다.
 *
 * @param consentType {@code tos} · {@code privacy} · {@code ai_notice} · {@code age}
 * @param version     사용자가 본 판본. <b>이것이 증빙의 실질</b>이다 (R10.2)
 * @param agreed      체크 여부. 거절도 값이다
 */
public record ConsentItem(@NotBlank String consentType, @NotBlank String version, boolean agreed) {

	SignupInfo.ConsentDecision toDecision() {
		return new SignupInfo.ConsentDecision(typeOf(this.consentType), this.version, this.agreed);
	}

	private static ConsentType typeOf(String value) {
		try {
			return ConsentType.valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, ex);
		}
	}
}
