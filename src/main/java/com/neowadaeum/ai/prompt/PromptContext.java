package com.neowadaeum.ai.prompt;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 조립기에 넘기는 한 턴의 재료 (B-20).
 *
 * <p><b>I-3 — 회원 식별정보를 담을 자리가 없다.</b> {@code TurnRequest} 와 같은 설계다. 이 레코드가
 * {@code ai} 모듈 밖의 엔티티를 알지 못하는 것이 그 보장의 구조적 절반이다.
 *
 * <p><b>I-7 — {@code SYSTEM} 과 {@code OUTPUT SPEC} 의 자리가 없다.</b> 작품이 채울 수 있는 것은
 * {@code worldPrompt} · {@code characters} 까지이며, 플랫폼 레이어는 {@link PlatformPrompts} 가
 * 소유한다.
 *
 * <p><b>이 컨텍스트를 채우는 것은 호출자다.</b> {@code world_prompt} · {@code persona_prompt} 를
 * catalog 에서 읽어 오는 배선은 프롬프트가 실제로 필요해지는 B-22 의 일이다 — {@code ai} 모듈은
 * 순수 DTO 만 받는다.
 *
 * @param worldPrompt  작품 세계관. 작품 레이어다
 * @param characters   등장인물 페르소나. 작품 레이어다
 * @param gameState    현재 GameState (§4.1). 서버가 만든다
 * @param summary      오래된 턴의 압축 (R4.5). 없으면 {@code null}
 * @param recentTurns  최근 턴 원문 (R4.7). <b>오래된 것이 앞</b>이며, 예산이 모자라면 앞에서부터 빠진다
 * @param userAction   이번 턴에 고른 선택지의 본문. 서버가 저장해 둔 값이다 (I-1). 첫 턴이면 {@code null}
 */
public record PromptContext(
		String worldPrompt,
		List<Character> characters,
		JsonNode gameState,
		String summary,
		List<RecentTurn> recentTurns,
		String userAction) {

	public PromptContext {
		if (worldPrompt == null || worldPrompt.isBlank()) {
			throw new IllegalArgumentException("worldPrompt is required");
		}
		if (gameState == null) {
			throw new IllegalArgumentException("gameState is required");
		}
		characters = List.copyOf(characters == null ? List.of() : characters);
		recentTurns = List.copyOf(recentTurns == null ? List.of() : recentTurns);
	}

	/**
	 * 등장인물 한 명 (§5.1 의 CHARACTER 레이어).
	 *
	 * @param name    작중 이름. 회원 정보가 아니라 작품 데이터다
	 * @param persona {@code character.persona_prompt}
	 */
	public record Character(String name, String persona) {

		public Character {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("character name is required");
			}
			if (persona == null || persona.isBlank()) {
				throw new IllegalArgumentException("persona is required");
			}
		}
	}

	/**
	 * 최근 턴 하나 (R4.7).
	 *
	 * <p><b>{@code SummaryRequest.TurnDigest} 와 모양이 같지만 합치지 않았다.</b> 하나는 요약에 넘길
	 * 대상이고 이것은 프롬프트에 실을 원문이다 — 지금 같은 것은 필드 세 개뿐이며, 한쪽이 바뀔 때
	 * 다른 쪽이 끌려가는 것이 더 비싸다 (§2.5 "코드 모양이 비슷하다는 이유로 합치지 않는다").
	 *
	 * <p><b>원문과 압축본을 둘 다 들고 온다</b> (§13-2). 어느 쪽을 실을지는 <b>턴의 위치</b>가
	 * 정하며 그 판단은 조립기의 몫이다 — 가장 최근 몇 턴만 원문이고 나머지는 압축본이다.
	 * 호출자가 미리 고르면 경계(설정값)가 두 곳에 생긴다.
	 *
	 * @param turnNo           세션 내 턴 번호
	 * @param chosenChoiceText 그 턴에서 고른 선택지 본문. 마지막 턴이면 {@code null}
	 * @param paragraphs       본문 원문. 없으면 {@code null} 이며 이때는 압축본이 쓰인다
	 * @param paragraphsDigest 본문 요지. <b>언제나 있어야 한다</b> — 원문이 예산에 들어가지 못할 때의 대안이다
	 */
	public record RecentTurn(int turnNo, String chosenChoiceText, String paragraphs, String paragraphsDigest) {

		public RecentTurn {
			if (turnNo <= 0) {
				throw new IllegalArgumentException("turnNo must be positive");
			}
			if (paragraphsDigest == null || paragraphsDigest.isBlank()) {
				throw new IllegalArgumentException("paragraphsDigest is required");
			}
		}
	}
}
