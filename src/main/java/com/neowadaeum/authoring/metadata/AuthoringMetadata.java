package com.neowadaeum.authoring.metadata;

import com.neowadaeum.authoring.outline.ConditionTemplate;
import java.util.List;

/**
 * 작성자가 고를 수 있는 값의 목록 (§13-56).
 *
 * @param genres            고를 수 있는 장르. {@code genre.display_order} 순서다
 * @param conditionTemplates 고를 수 있는 조건 템플릿. 각자 필요한 입력을 스스로 선언한다 (#282)
 */
public record AuthoringMetadata(List<GenreOption> genres, List<ConditionTemplate> conditionTemplates) {

	/**
	 * 장르 하나.
	 *
	 * @param key   API 표기. 라이브러리 섹션 키 {@code genre:<key>} 가 쓰는 값과 같다
	 * @param label 화면 문구. 정본은 {@code genre} 표이며 배포 없이 바뀔 수 있다
	 */
	public record GenreOption(String key, String label) {
	}
}
