package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.metadata.AuthoringMetadata;
import com.neowadaeum.authoring.outline.ConditionParameter;
import com.neowadaeum.authoring.outline.ConditionTemplate;
import java.util.List;

/**
 * 작품 만들기가 고르게 할 값 (§13-56, 이슈 #282 · #315).
 *
 * <p><b>키와 라벨이 함께 간다.</b> 키만 주면 프론트가 한국어 문구를 스스로 만들고, 그 순간
 * <b>표시 문구의 정본이 프론트에 생긴다</b> — {@code noticeText}(#257) · {@code ConsentItem.version}
 * (#261) 과 정확히 같은 종류의 문제다.
 */
public record AuthoringMetadataResponse(List<AuthoringGenre> genres,
		List<ConditionTemplateSpec> conditionTemplates) {

	static AuthoringMetadataResponse of(AuthoringMetadata metadata) {
		return new AuthoringMetadataResponse(
				metadata.genres().stream()
						.map(genre -> new AuthoringGenre(genre.key(), genre.label())).toList(),
				metadata.conditionTemplates().stream().map(ConditionTemplateSpec::of).toList());
	}

	/**
	 * @param key   라이브러리 섹션 키 {@code genre:<key>} 가 쓰는 값과 같다
	 * @param label 화면 문구. 정본은 {@code genre} 표다
	 */
	public record AuthoringGenre(String key, String label) {
	}

	/**
	 * <b>키만으로는 조건이 완성되지 않는다</b> (#282).
	 *
	 * @param parameters 이 템플릿을 쓰려면 채워야 하는 값. 비어 있는 템플릿은 지금 없다
	 */
	public record ConditionTemplateSpec(String key, String label, String description,
			List<ConditionTemplateParameter> parameters) {

		static ConditionTemplateSpec of(ConditionTemplate template) {
			return new ConditionTemplateSpec(template.key(), template.label(), template.description(),
					template.parameters().stream().map(ConditionTemplateParameter::of).toList());
		}
	}

	/**
	 * @param type {@code character} · {@code flag} · {@code integer}. <b>자유 텍스트가 아니다</b> —
	 *             {@code character} 와 {@code flag} 의 선택지는 <b>그 원고</b>에서 온다
	 */
	public record ConditionTemplateParameter(String name, String type, String label) {

		static ConditionTemplateParameter of(ConditionParameter parameter) {
			return new ConditionTemplateParameter(parameter.name(), parameter.type().key(),
					parameter.label());
		}
	}
}
