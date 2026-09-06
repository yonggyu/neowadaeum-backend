package com.neowadaeum.authoring.draft;

import com.neowadaeum.authoring.UgcLimitProperties;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 원고 하나가 <b>검수 한 번의 크기</b>를 정하지 못하게 한다 (§13-81, R8.12, #380).
 *
 * <p><b>이 게이트가 재는 것은 챕터와 엔딩의 개수다.</b> L1 은 작품의 텍스트를 필드 지도로 펼쳐
 * 한 번에 판정기에 넘기고 ({@code SubmissionService.fieldsOf}), 그 지도의 크기는 챕터·엔딩
 * 개수에 비례한다 — 상한이 없으면 <b>원고 하나가 제출 한 번의 비용을 정한다.</b> R8.12 가
 * 작성자당 작품 수에 상한을 둔 것과 같은 종류의 이유이며, 거기서 세지 않은 축이다.
 *
 * <p><b>{@link DraftVocabularyGate} 와 다른 축이다.</b> 그쪽이 재는 것은 인물·플래그가 만들
 * <b>프롬프트 어휘 블록</b>이고 (§13-76), 단위는 개수가 아니라 토큰 예산이다. 챕터·엔딩은 그
 * 블록에 실리지 않으므로 그 게이트를 그대로 지나간다. 두 축이 <b>함께</b> 필드 지도의 크기를
 * 정하며, 한쪽만 있으면 다른 쪽이 그 상한을 무의미하게 만든다 — #367 이 겪은 실패가 그것이다.
 *
 * <p><b>L0 의 요청당 상한과도 다른 축이다.</b> {@code PrecheckRequest} 가 막는 것은 <b>타자
 * 한 번이 부르는 한 요청</b>의 크기이고 (화면이 넘긴 필드 지도), 여기서 막는 것은 <b>작품
 * 하나</b>의 크기다. 같은 값을 복제하지 않는다.
 *
 * <p><b>왜 저장 시점인가.</b> §13-76 이 어휘에서 내린 결론과 같다 — 승인은 곧 게시이고 (R8.8)
 * 세션은 생성 시 버전에 고정되므로 (I-4), 발행된 뒤에는 되돌릴 수 없다. 작성자가 아직 고칠 수
 * 있는 자리는 원고다. 미리보기와 제출에도 서는 것은 <b>게이트가 붙기 전에 저장된 원고</b>가
 * 그 길로 오기 때문이다.
 */
@Component
public class DraftScaleGate {

	private final UgcLimitProperties limits;

	public DraftScaleGate(UgcLimitProperties limits) {
		this.limits = limits;
	}

	/**
	 * @throws ApiException {@code VALIDATION_ERROR} — 넘겼다. {@code details} 로 <b>어느 목록이
	 *     몇 개까지인지</b> 알린다. 세이프티 임계가 아니므로 값을 가리지 않는다 (S-11 이 가리는
	 *     것은 검수 비율과 정지 임계다) — 가리면 작성자는 몇 개를 지워야 하는지 알 수 없다
	 */
	public void verify(DraftStoryDefinition.Declared declared) {
		requireAtMost("chapters", declared.chapterCount(), this.limits.chaptersPerStory());
		requireAtMost("endings", declared.endingCount(), this.limits.endingsPerStory());
	}

	private static void requireAtMost(String field, int count, int max) {
		if (count > max) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", field, "max", max));
		}
	}
}
