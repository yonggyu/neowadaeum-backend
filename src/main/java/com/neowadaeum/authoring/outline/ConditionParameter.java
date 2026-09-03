package com.neowadaeum.authoring.outline;

/**
 * 조건 템플릿이 하나 요구하는 입력 (R7.16, §13-56).
 *
 * @param name  값을 담을 자리의 이름. 템플릿 안에서 유일하다
 * @param type  화면이 무엇을 그려야 하는가
 * @param label 사람이 읽는 문구. <b>정본은 서버에 있다</b> — 프론트가 옮기기 시작하면 표시 문구의
 *              정본이 하나 더 생긴다 (#282)
 */
public record ConditionParameter(String name, ConditionParameterType type, String label) {

	public ConditionParameter {
		if (name == null || name.isBlank() || type == null || label == null || label.isBlank()) {
			throw new IllegalArgumentException("name, type, label are required");
		}
	}

	static ConditionParameter character(String name, String label) {
		return new ConditionParameter(name, ConditionParameterType.CHARACTER, label);
	}

	static ConditionParameter flag(String name, String label) {
		return new ConditionParameter(name, ConditionParameterType.FLAG, label);
	}

	static ConditionParameter integer(String name, String label) {
		return new ConditionParameter(name, ConditionParameterType.INTEGER, label);
	}
}
