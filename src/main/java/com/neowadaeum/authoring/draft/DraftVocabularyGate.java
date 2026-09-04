package com.neowadaeum.authoring.draft;

import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.common.spi.StateVocabularyBudget;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 선언한 이름이 프롬프트에 실릴 수 있는지 (§13-76, #367).
 *
 * <p><b>왜 개수와 길이가 게이트가 아닌가.</b> §13-73 은 플래그 32개 · 40자를 <b>프롬프트 때문에</b>
 * 정했다고 적었지만, 그 프롬프트의 상한과는 대조되지 않았다 — 실제로는 그 두 배를 넘는다. 그리고
 * {@code characters} 에는 상한이 아예 없다. 그러니 세는 것을 바꾼다: <b>이 원고가 만들 어휘
 * 블록이 자기 예산에 들어가는가.</b>
 *
 * <p><b>상한 값을 여기 적지 않는다.</b> 그 숫자는 {@code ai} 의 것이고 물어서 답을 받는다
 * ({@link StateVocabularyBudget}). 여기 복제하면 {@code ai} 가 레이어 문구를 고치는 날 조용히
 * 어긋나고, <b>그 어긋남은 작성자의 작품이 한 턴도 돌지 않을 때</b> 드러난다.
 *
 * <p><b>왜 저장 시점인가.</b> 넘긴 어휘는 발행되면 되돌릴 수 없다 — 세션은 생성 시 버전에
 * 고정되므로 (I-4) 이미 시작된 플레이는 새 버전으로 구제되지 않고, 그 작품은 <b>모든 턴에서</b>
 * 실패한다. 작성자가 아직 고칠 수 있는 자리는 원고다.
 */
@Component
public class DraftVocabularyGate {

	/** {@code state_schema} 의 수치 그룹. {@link DraftStateSchema} 가 이 이름으로 발행한다. */
	private static final String AFFINITY = "affinity.";

	private final StateVocabularyBudget budget;

	public DraftVocabularyGate(StateVocabularyBudget budget) {
		this.budget = budget;
	}

	/**
	 * <b>인물과 플래그를 함께 본다.</b> 둘 다 같은 블록에 실리므로 한쪽만 재면 다른 쪽이 그
	 * 상한을 무의미하게 만든다 — 상한이 없는 쪽이 인물이다.
	 *
	 * @throws ApiException {@code VALIDATION_ERROR} — 넘겼다. {@code details.vocabularyUsagePercent}
	 *     로 <b>얼마나 넘겼는지</b> 알린다. 토큰 수도 상한도 내보내지 않는다 (S-6)
	 */
	public void verify(DraftStateSchema declared) {
		List<String> numericPaths = declared.characters().stream().map(AFFINITY::concat).toList();

		StateVocabularyBudget.Usage usage = this.budget.assess(numericPaths, declared.flags(), List.of());
		if (!usage.fits()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR,
					Map.of("vocabularyUsagePercent", usage.percentOfBudget()));
		}
	}
}
