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
 * <p><b>{@code STATE VOCABULARY} 도 마찬가지다</b> (§13-76). 이 레코드가 나르는 것은 <b>이름
 * 목록</b>이고 그것을 소개하는 문장은 {@link PlatformPrompts} 에 있다 — 작품이 넣을 수 있는 것은
 * 목록의 한 항목뿐이라 <b>그 자리에서 지시문이 될 수 없다.</b>
 *
 * <p><b>이 컨텍스트를 채우는 것은 호출자다.</b> {@code world_prompt} · {@code persona_prompt} 를
 * catalog 에서 읽어 오는 배선은 프롬프트가 실제로 필요해지는 B-22 의 일이다 — {@code ai} 모듈은
 * 순수 DTO 만 받는다.
 *
 * @param worldPrompt  작품 세계관. 작품 레이어다
 * @param characters   등장인물 페르소나. 작품 레이어다
 * @param gameState    현재 GameState (§4.1). 서버가 만든다
 * @param stateVocabulary {@code state_schema} 가 선언한 이름 (§13-76). <b>값이 아니라 이름이다</b>
 * @param summary      오래된 턴의 압축 (R4.5). 없으면 {@code null}
 * @param recentTurns  최근 턴 원문 (R4.7). <b>오래된 것이 앞</b>이며, 예산이 모자라면 앞에서부터 빠진다
 * @param userAction   이번 턴에 고른 선택지의 본문. 서버가 저장해 둔 값이다 (I-1). 첫 턴이면 {@code null}
 */
public record PromptContext(
		String worldPrompt,
		List<Character> characters,
		JsonNode gameState,
		StateVocabulary stateVocabulary,
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
		// 빈 어휘와 빠뜨린 어휘를 같은 null 로 두지 않는다 — 배선 실수가 프롬프트에서만 드러난다.
		if (stateVocabulary == null) {
			throw new IllegalArgumentException("stateVocabulary is required; use StateVocabulary.none()");
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
	 * {@code state_schema} 가 선언한 이름 (§13-76).
	 *
	 * <p><b>{@code GenerationContext.StateVocabulary} 와 모양이 같지만 합치지 않았다.</b>
	 * {@code Character} · {@code RecentTurn} 과 같은 이유다 — 경계는 {@link TurnPromptFactory}
	 * 에서 한 번만 넘어간다.
	 *
	 * <p><b>여기서도 정렬해 둔다.</b> 이 레코드는 포트를 거치지 않고 직접 만들어지기도 하며
	 * (테스트·골든), 정렬을 한쪽에만 두면 <b>어느 입구로 들어왔는가</b>가 프롬프트를 바꾼다.
	 *
	 * @param numerics  수치 경로. {@code stateChanges} 의 키와 같은 표기다
	 * @param flags     {@code flags.add} / {@code flags.remove} 가 쓸 수 있는 이름
	 * @param inventory {@code inventory.add} / {@code inventory.remove} 가 쓸 수 있는 이름
	 */
	public record StateVocabulary(List<String> numerics, List<String> flags, List<String> inventory) {

		public StateVocabulary {
			numerics = canonical(numerics);
			flags = canonical(flags);
			inventory = canonical(inventory);
		}

		/** 아무것도 선언되지 않은 작품. 이 레이어는 통째로 빠진다. */
		public static StateVocabulary none() {
			return new StateVocabulary(List.of(), List.of(), List.of());
		}

		/**
		 * <b>{@code isEmpty} 라고 부르지 않는다.</b> 포트 쪽 쌍둥이는 페이로드로 직렬화되고,
		 * Jackson 은 {@code isXxx()} 를 필드로 읽어 <b>선언에 없는 이름</b>을 내보낸다 (I-3).
		 * 두 레코드의 이름을 갈라 두면 다음 사람이 옮겨 적을 때 그 함정을 다시 밟는다.
		 */
		public boolean declaresNothing() {
			return this.numerics.isEmpty() && this.flags.isEmpty() && this.inventory.isEmpty();
		}

		private static List<String> canonical(List<String> names) {
			return names == null ? List.of() : names.stream().distinct().sorted().toList();
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
