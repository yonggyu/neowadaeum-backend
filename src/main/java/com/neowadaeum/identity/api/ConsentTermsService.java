package com.neowadaeum.identity.api;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.ConsentTerm;
import com.neowadaeum.common.spi.ConsentTermsQuery;
import com.neowadaeum.identity.auth.AgeGate;
import com.neowadaeum.identity.auth.SignupInfo;
import com.neowadaeum.identity.domain.ConsentType;
import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 가입 화면에 줄 약관 메타를 조립한다 (이슈 #261, R10.2).
 *
 * <p><b>판본이 코드에 없다.</b> {@code tos} · {@code privacy} 는 설정에서 오고
 * ({@link ConsentTermsQuery}), {@code ai_notice} 는 사용자가 본 고지의 판본을 그대로 쓰며,
 * {@code age} 는 <b>서버의 판정 기준</b>이 곧 판본이다 (§13-24) — 셋 다 이 클래스가 정하는 값이
 * 아니다.
 *
 * <p><b>설정이 없으면 실패한다.</b> {@code "v1"} 같은 기본값을 주면 이슈 #261 이 고치려던 상태 —
 * <b>아무도 검증하지 않는 상수 판본이 동의 이력에 남는 것</b> — 이 서버 쪽으로 옮겨올 뿐이고,
 * 그때는 프론트가 상수를 들고 있을 때보다 <b>찾기 더 어렵다.</b> {@code LandingService} 가 고지
 * 문구에 대해 하는 것과 같은 판단이다 (R11.1).
 */
@Service
public class ConsentTermsService {

	private static final Logger log = LoggerFactory.getLogger(ConsentTermsService.class);

	private final ConsentTermsQuery terms;

	public ConsentTermsService(ConsentTermsQuery terms) {
		this.terms = terms;
	}

	/**
	 * @throws ApiException {@code INTERNAL_ERROR} — 약관 판본이 설정되지 않았다. <b>운영 결함이며
	 *     조용히 넘어가서는 안 된다</b> (R10.2)
	 */
	public ConsentTermsView terms() {
		return new ConsentTermsView(Arrays.stream(ConsentType.values()).map(this::termOf).toList());
	}

	private ConsentTermsView.Term termOf(ConsentType type) {
		String consentType = type.name().toLowerCase(Locale.ROOT);
		if (type == ConsentType.AGE) {
			// 사용자가 체크하는 항목이 아니다. 판본도 서버가 정한다 (§13-24, R10.2).
			return new ConsentTermsView.Term(consentType, AgeGate.consentVersion(), null, false);
		}
		ConsentTerm term = this.terms.find(consentType).orElseThrow(() -> {
			log.error("consent.terms.missing consentType={} — 판본 없이 가입 화면을 내보내지 않는다 (R10.2)",
					consentType);
			return new ApiException(ErrorCode.INTERNAL_ERROR);
		});
		return new ConsentTermsView.Term(consentType, term.version(), term.documentUrl(),
				SignupInfo.REQUIRED.contains(type));
	}
}
