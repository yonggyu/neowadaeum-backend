package com.neowadaeum.identity.auth;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * 가입 연령 게이트 (R10.2, §4.1, B-13).
 *
 * <p><b>만 15세 미만은 가입을 거부한다.</b> 서비스 전체가 15세 이용가 단일 등급이며(§10),
 * <b>만 15세 미만을 받지 않으므로 만 14세 미만 법정대리인 동의 절차가 불필요해진다</b> (R10.3).
 * 그 전제가 무너지는 것은 19금 도입 시점이고, 그때는 별도 프로젝트다 (R10.5).
 *
 * <p><b>KST 로 판정한다.</b> 계약이 그렇게 적었다. 서버가 어느 시간대에 뜨든 <b>같은 사람이 같은
 * 날 통과</b>해야 하며, UTC 로 재면 한국 시각 자정~오전 9시 사이에 생일이 하루 늦게 온다.
 *
 * <p><b>나이를 저장하지 않는다.</b> {@code birth_date} 원본으로 그때그때 계산한다 (§2.2) —
 * 캐시는 반드시 틀린 날이 온다.
 */
@Component
public class AgeGate {

	/** §10 — 서비스 단일 등급이 15세 이용가다. */
	static final int MINIMUM_AGE = 15;

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final Clock clock;

	public AgeGate(Clock clock) {
		this.clock = clock;
	}

	/**
	 * {@code age} 동의의 판본 (§13-24, R10.2).
	 *
	 * <p><b>판정 기준을 그대로 판본으로 쓴다.</b> 그 값이 곧 "무엇을 확인했는가"이며, 기준이
	 * 바뀌면 판본도 함께 바뀌어야 한다 — 두 곳에 따로 적으면 한쪽만 바뀐 날 이력이 거짓이 된다.
	 * 기록하는 쪽({@code SocialAccountRegistrar})과 알려 주는 쪽(가입 화면, 이슈 #261)이 <b>같은
	 * 출처</b>를 보게 하는 것이 이 메서드의 존재 이유다.
	 */
	public static String consentVersion() {
		return "age-" + MINIMUM_AGE;
	}

	/**
	 * 만 {@value #MINIMUM_AGE} 세가 됐는가.
	 *
	 * <p><b>생일 당일은 통과한다.</b> 만 나이는 생일에 오르므로 그날 이미 15세다 — 경계가
	 * 하루 어긋나면 그날 가입한 사람이 전부 거부된다 (§10.1-13).
	 */
	public boolean isEligible(LocalDate birthDate) {
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), KST);
		return !birthDate.plusYears(MINIMUM_AGE).isAfter(today);
	}

	/**
	 * 통과하지 못하면 던진다.
	 *
	 * @throws ApiException {@code AGE_RESTRICTED} — <b>계정을 만들지 않는다</b> (R10.2).
	 *     거부하고 계정만 남기면 그 계정은 나이를 확인받지 않은 채 존재하게 된다
	 */
	public void requireEligible(LocalDate birthDate) {
		if (!isEligible(birthDate)) {
			throw new ApiException(ErrorCode.AGE_RESTRICTED);
		}
	}
}
