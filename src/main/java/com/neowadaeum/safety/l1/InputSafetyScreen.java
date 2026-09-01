package com.neowadaeum.safety.l1;

import com.neowadaeum.safety.l2.RuleBasedSafetyJudge;
import com.neowadaeum.safety.l2.SafetyJudgement;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 사람이 넣은 텍스트의 검수 (B-43, I-17, R14.1).
 *
 * <p><b>같은 대조기를 쓴다.</b> 블록리스트 매칭을 여기에 따로 구현하면 두 곳이 서서히 갈라지고,
 * 그러면 <b>한쪽으로 들어온 문자열이 다른 쪽에서는 걸리는</b> 상태가 된다. 정규화와 대조는
 * {@link RuleBasedSafetyJudge} 하나에 둔다 — 입력이냐 출력이냐로 달라질 규칙이 아니다.
 *
 * <p><b>달라지는 것은 그 뒤다.</b> 출력이 걸리면 재생성이라는 선택지가 있지만, 입력이 걸리면
 * 다시 만들 것이 없다 — 넣은 사람에게 돌려보내는 것 말고 할 일이 없다.
 *
 * <p><b>관리자라는 사실이 검수를 면제하지 않는다</b> (I-17). 디버그 목적이어도 무검열 통로를
 * 만들지 않는다 — 통로가 하나 있으면 그것이 곧 그 서비스의 실제 등급이 된다.
 *
 * <p><b>fail-closed</b> — 대조기가 조회에 실패하면 차단으로 답한다 (ADR-0002). 그 성질은
 * 대조기가 갖고 있으며 여기서 뒤집지 않는다.
 */
@Component
public class InputSafetyScreen {

	private final RuleBasedSafetyJudge rules;

	public InputSafetyScreen(RuleBasedSafetyJudge rules) {
		this.rules = rules;
	}

	/**
	 * 이 텍스트를 들여도 되는가.
	 *
	 * <p><b>빈 입력은 검수 대상이 아니다.</b> 들일 것이 없으므로 통과도 차단도 아니며, 그
	 * 판단(무엇이 유효한 입력인가)은 부르는 쪽의 몫이다.
	 *
	 * <p><b>원문을 로그에 남기지 않는다</b> (S-3). 걸린 입력일수록 남기고 싶어지지만, 그것을
	 * 남기는 순간 애플리케이션 로그가 우회 표기 사전이 된다 (S-11).
	 */
	public InputVerdict screen(String text) {
		if (text == null || text.isBlank()) {
			return InputVerdict.pass();
		}
		SafetyJudgement judgement = this.rules.judge(List.of(text), List.of());
		// R9.2 — **입력에서는 걸린 것이 곧 거부다.** 출력의 처리(재생성·마스킹)를 여기로 가져오지
		// 않는다: 다시 만들 것도 없고, 넣은 사람의 문장을 서버가 고쳐서 들이지도 않는다.
		// 특히 마스킹은 "생성물은 가린 뒤 통과, UGC 입력은 차단"이므로 여기서 통과가 되면 안 된다.
		//
		// 차단 판정을 따로 보는 것은 **걸린 항목 없이 차단되는 경우**가 있기 때문이다 — 조회
		// 실패는 카테고리를 남기지 않으면서 차단이다 (ADR-0002 fail-closed).
		boolean blocked = judgement.blocked() || !judgement.categories().isEmpty();
		return new InputVerdict(blocked, judgement.categories());
	}
}
