package com.neowadaeum.ai.prompt;

import com.neowadaeum.play.port.GenerationContext;
import com.neowadaeum.play.port.TurnRequest;

/**
 * 포트 계약을 프롬프트로 옮긴다 (B-22, §5.1).
 *
 * <p><b>경계가 여기서 한 번만 넘어간다.</b> {@link GenerationContext} 는 {@code play} 가 소유한
 * 계약이고 {@link PromptContext} 는 {@code ai} 내부 타입이다. 둘을 하나로 합치지 않는 이유는
 * <b>예산과 레이어 정책이 {@code ai} 의 것</b>이기 때문이다 — 합치면 §13-2 의 경계값을 바꿀 때
 * {@code play} 가 함께 바뀐다.
 *
 * <p><b>어댑터마다 이 매핑을 복제하지 않는다.</b> B-22 · B-23 이 둘 다 프롬프트를 필요로 하고,
 * 각자 옮기면 <b>한쪽만 필드를 빠뜨렸을 때 그 사실이 프롬프트가 이상하다는 증상으로만 나타난다.</b>
 *
 * <p><b>I-7 — 여기서 플랫폼 레이어를 만들지 않는다.</b> {@code SYSTEM} 과 {@code OUTPUT SPEC} 은
 * {@link PlatformPrompts} 가 소유하고 {@link PromptAssembler} 가 붙인다. 이 클래스가 옮기는 것은
 * <b>작품 레이어와 서버가 만든 상태까지</b>다.
 */
public class TurnPromptFactory {

	private final PromptAssembler assembler;

	public TurnPromptFactory(PromptAssembler assembler) {
		this.assembler = assembler;
	}

	public AssembledPrompt create(TurnRequest request) {
		return this.assembler.assemble(toPromptContext(request.context()));
	}

	private static PromptContext toPromptContext(GenerationContext context) {
		return new PromptContext(
				context.worldPrompt(),
				context.characters().stream()
						.map(character -> new PromptContext.Character(character.name(), character.persona()))
						.toList(),
				context.gameState(),
				context.summary(),
				context.recentTurns().stream().map(TurnPromptFactory::toRecentTurn).toList(),
				context.userAction());
	}

	/**
	 * <b>압축본이 없으면 원문을 쓴다.</b> 요약 파이프라인(B-34)이 붙기 전까지
	 * {@code paragraphsDigest} 는 언제나 {@code null} 이며, 조립기는 압축본 자리에 원문을 받는다.
	 *
	 * <p>그 결과 <b>예산을 더 쓴다.</b> 조용히 넘기지 않는 것이 요점이다 — 예산을 넘기면
	 * 조립기가 오래된 턴부터 통째로 빼고, 그래도 넘치면 실패시킨다 (§4.4). 빈 문자열을 넣어
	 * "압축본이 있다"고 꾸미면 그 실패가 사라지고 대신 <b>맥락이 조용히 사라진다.</b>
	 */
	private static PromptContext.RecentTurn toRecentTurn(GenerationContext.RecentTurn turn) {
		String digest = (turn.paragraphsDigest() != null && !turn.paragraphsDigest().isBlank())
				? turn.paragraphsDigest() : turn.paragraphs();

		return new PromptContext.RecentTurn(turn.turnNo(), turn.chosenChoiceText(), turn.paragraphs(), digest);
	}
}
