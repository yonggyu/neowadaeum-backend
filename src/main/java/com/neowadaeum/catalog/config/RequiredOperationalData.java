package com.neowadaeum.catalog.config;

import com.neowadaeum.common.spi.AiNoticeQuery;
import com.neowadaeum.common.spi.ConsentTermsQuery;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * 없으면 서비스가 성립하지 않는 운영 데이터의 <b>정본 목록</b> (§13-89, 이슈 #426).
 *
 * <p><b>목록이 코드에 하나여야 하는 이유.</b> 이 값들이 막는 화면은 이미 여덟이고 앞으로도 는다
 * (§13-52). 목록을 문서나 절차서에 두면 화면이 늘어도 따라가지 않는다 — 실제로 그렇게 어긋난
 * 표가 있었다. 늘리는 자리와 검사하는 자리가 같아야 한다.
 *
 * <p><b>셋인 이유 — 독립된 값만 센다.</b> 동의 판본은 넷이지만 그중 둘은 여기서 셀 값이 아니다
 * (§13-51): {@code age} 의 판본은 서버가 조립하고({@code AgeGate}), {@code ai_notice} 의 판본은
 * {@code ai.notice} 에서 파생된다. 파생값을 목록에 넣으면 <b>같은 결함이 두 번 보고된다.</b>
 *
 * <p><b>이 목록은 "무엇이 없는가"만 말한다.</b> 없을 때 무엇을 할지는 부르는 쪽이 정한다 —
 * 기동 시점은 {@link RequiredOperationalDataCheck} 이고, 요청 시점의 응답은 그대로 500 이다
 * (§13-27, §13-51). 이 열거형은 그 판단을 바꾸지 않는다.
 */
public enum RequiredOperationalData {

	/** AI 사전 고지 문구 (R11.1). 없으면 고지를 싣는 화면 전부가 500 이다. */
	AI_NOTICE("ai.notice", (notices, terms) -> notices.current().isPresent()),

	/** 이용약관 판본 (R10.2). 없으면 가입 화면이 판본을 얻지 못한다. */
	CONSENT_TERMS_TOS("consent.terms.tos", (notices, terms) -> terms.find("tos").isPresent()),

	/** 개인정보 처리방침 판본 (R10.2). {@code tos} 와 각자 센다 (§13-51). */
	CONSENT_TERMS_PRIVACY("consent.terms.privacy", (notices, terms) -> terms.find("privacy").isPresent());

	/**
	 * 운영자가 읽을 이름이자 조회 표기.
	 *
	 * <p>동의 종류의 표기는 계약의 것을 그대로 쓴다 — {@code identity} 의 열거형을 참조하지
	 * 않는다 (모듈 경계: catalog → common). 값의 모양을 아는 곳은 {@link CatalogConsentTermsQuery}
	 * 이며 그 표기도 거기서 정한 것이다.
	 */
	private final String label;

	private final BiPredicate<AiNoticeQuery, ConsentTermsQuery> configured;

	RequiredOperationalData(String label, BiPredicate<AiNoticeQuery, ConsentTermsQuery> configured) {
		this.label = label;
		this.configured = configured;
	}

	/** <b>값이 아니라 자리의 이름</b>이다 — 이것을 로그에 남겨도 문구가 실리지 않는다 (S-3). */
	public String label() {
		return this.label;
	}

	/**
	 * 지금 없는 것들의 이름.
	 *
	 * @return 빠짐없이 들어 있으면 빈 목록. <b>첫 번째에서 멈추지 않는다</b> — 하나를 넣고 다시
	 *     띄웠더니 다음 하나가 나오는 절차는 배포를 그 수만큼 반복하게 만든다
	 */
	public static List<String> missing(AiNoticeQuery notices, ConsentTermsQuery terms) {
		return Arrays.stream(values())
				.filter(required -> !required.configured.test(notices, terms))
				.map(RequiredOperationalData::label)
				.toList();
	}
}
