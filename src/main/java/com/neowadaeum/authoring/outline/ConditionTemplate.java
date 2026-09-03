package com.neowadaeum.authoring.outline;

import java.util.List;
import java.util.Locale;

/**
 * 작성자가 고를 수 있는 조건 (R7.16, §13-35).
 *
 * <p><b>작성자가 조건식을 직접 쓰지 않는다.</b> DSL 을 열면 두 가지가 따라온다 — 아무도 못 쓰는
 * 화면이 되거나, 쓸 수 있는 사람이 <b>조건 평가기의 미정의 동작</b>을 찾아낸다.
 *
 * <p><b>목록이 짧은 것은 의도다.</b> §4.5 · §4.6 의 조건 문법 전부를 노출할 이유가 없다 —
 * 필요가 실제로 생기면 그때 늘린다.
 *
 * <p><b>키만으로는 조건이 완성되지 않는다</b> (#282, §13-56). {@code ConditionEvaluator} 가 읽는
 * 것은 {@code {"gte": ["affinity.<인물>", <임계>]}} 이고, 그 두 값 중 어느 것도 키 안에 접혀 있지
 * 않다. 그래서 템플릿마다 <b>필요한 입력을 스스로 선언</b>한다 — 선언이 없으면 화면은 무엇을
 * 물어야 하는지 알 수 없고, 그 지식이 프론트로 새면 계약이 다시 거짓말을 시작한다.
 *
 * <p><b>정본이 코드인 이유.</b> 장르는 운영 데이터라 {@code genre} 표에서 오지만, 이 넷은
 * <b>평가기가 지원하는 형태</b>다 — 표에 한 줄을 더해도 그 조건을 평가할 코드가 없다. 운영이
 * 늘릴 수 없는 목록을 운영 데이터로 두면, 늘어난 항목이 조용히 {@code false} 가 된다
 * ({@code ConditionEvaluator} 의 미정의 키 처리).
 */
public enum ConditionTemplate {

	/** 특정 인물의 호감도가 임계 이상. {@code {"gte": ["affinity.<인물>", <임계>]}} 로 조립된다. */
	AFFINITY_AT_LEAST("호감도 이상", "고른 인물의 호감도가 임계값 이상일 때",
			List.of(ConditionParameter.character("character", "인물"),
					ConditionParameter.integer("threshold", "임계값"))),

	/** 특정 플래그를 갖고 있다. {@code {"has": ["flags", "<플래그>"]}} 로 조립된다. */
	HAS_FLAG("플래그 보유", "고른 플래그를 갖고 있을 때",
			List.of(ConditionParameter.flag("flag", "플래그"))),

	/** 특정 플래그를 갖고 있지 않다. {@code {"not": {"has": ["flags", "<플래그>"]}}} 로 조립된다. */
	LACKS_FLAG("플래그 미보유", "고른 플래그를 갖고 있지 않을 때",
			List.of(ConditionParameter.flag("flag", "플래그"))),

	/** 턴 수가 임계 이상. {@code {"turnGte": <임계>}} 로 조립된다. */
	TURN_AT_LEAST("턴 수 이상", "누적 턴 수가 임계값 이상일 때",
			List.of(ConditionParameter.integer("threshold", "턴 수")));

	private final String label;

	private final String description;

	private final List<ConditionParameter> parameters;

	ConditionTemplate(String label, String description, List<ConditionParameter> parameters) {
		this.label = label;
		this.description = description;
		this.parameters = List.copyOf(parameters);
	}

	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** 사람이 읽는 문구. <b>정본은 서버다</b> — 프론트가 옮기면 표시 문구의 정본이 하나 더 생긴다. */
	public String label() {
		return this.label;
	}

	public String description() {
		return this.description;
	}

	/** 이 템플릿을 쓰려면 채워야 하는 값. 비어 있는 템플릿은 지금 없다. */
	public List<ConditionParameter> parameters() {
		return this.parameters;
	}
}
