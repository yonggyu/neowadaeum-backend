package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.identity.domain.ConsentType;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 최초 로그인(가입)에만 필요한 값 (§4.1, §13-24).
 *
 * <p><b>기존 회원은 이것을 보내지 않는다.</b> 로그인할 때마다 동의를 다시 받으면 동의 이력이
 * 로그인 이력이 된다 — 그러면 "언제 무엇에 동의했는가"가 흐려진다.
 *
 * @param birthDate 만 나이 계산의 원본 (§2.2). 저장되고, 나이는 저장되지 않는다
 * @param consents  사용자가 화면에서 동의한 항목들
 */
public record SignupInfo(LocalDate birthDate, List<ConsentDecision> consents) {

	/**
	 * 가입에 반드시 필요한 동의 (§4.1 — 약관·개인정보·AI고지).
	 *
	 * <p>{@link ConsentType#AGE} 는 여기에 없다. <b>그것은 사용자가 체크하는 것이 아니라 서버가
	 * 생년월일로 판정한 사실</b>이며, 서버가 스스로 기록한다 (R10.2).
	 *
	 * <p><b>가입 화면이 무엇을 체크박스로 그릴지도 이 목록이 정한다</b> (이슈 #261) — 프론트가
	 * 같은 목록을 따로 들고 있으면 항목이 늘어난 날 한쪽만 바뀐다.
	 */
	public static final Set<ConsentType> REQUIRED =
			Set.of(ConsentType.TOS, ConsentType.PRIVACY, ConsentType.AI_NOTICE);

	public SignupInfo {
		consents = List.copyOf(consents == null ? List.of() : consents);
	}

	/**
	 * 가입에 필요한 것이 다 왔는가.
	 *
	 * @throws ApiException {@code CONSENT_REQUIRED} — <b>무엇이 빠졌는지 세분해 알리지 않는다.</b>
	 *     클라이언트는 어차피 세 항목을 함께 보여 준다 (§13.1 — 코드로 문구를 매핑한다)
	 */
	void requireComplete() {
		if (this.birthDate == null || !agreedTypes().containsAll(REQUIRED)) {
			throw new ApiException(ErrorCode.CONSENT_REQUIRED);
		}
	}

	private Set<ConsentType> agreedTypes() {
		return this.consents.stream()
				.filter(ConsentDecision::agreed)
				.map(ConsentDecision::type)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	/**
	 * 동의 한 건.
	 *
	 * <p>{@code agreed = false} 도 값이다 — 선택 동의를 거절한 사실이며, 필수 항목이면
	 * {@link #requireComplete()} 가 거른다.
	 *
	 * @param version 사용자가 본 약관·방침의 판본. <b>이것이 증빙의 실질</b>이다 (R10.2)
	 */
	public record ConsentDecision(ConsentType type, String version, boolean agreed) {

		public ConsentDecision {
			if (type == null || version == null || version.isBlank()) {
				// 판본 없는 동의는 증빙이 되지 못한다.
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			}
		}
	}
}
