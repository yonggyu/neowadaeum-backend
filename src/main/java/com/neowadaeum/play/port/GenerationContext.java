package com.neowadaeum.play.port;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 한 턴을 쓰는 데 필요한 재료 (§5.1, B-22).
 *
 * <p><b>왜 포트가 이것을 나르는가.</b> 프롬프트의 재료는 전부 {@code play} 와 {@code catalog} 에
 * 있다 — 세계관 · 페르소나 · GameState · 요약 · 최근 턴 · 고른 선택지 본문. {@code ai} 는 그것을
 * 읽을 수 없고(ADR-0006 이후 방향은 {@code ai → play :: port} 하나다), 읽어서도 안 된다
 * ({@code ai} 는 도메인 모듈을 참조하지 않는다). <b>그래서 재료가 계약을 타고 건너간다.</b>
 *
 * <p><b>I-3 — 회원 식별정보를 담을 자리가 없다.</b> 여기 있는 것은 전부 <b>작품 데이터와 서버가
 * 만든 상태</b>다. {@code playerRef} · 이메일 · 생년월일은 필드로 존재하지 않으며, 그 위에
 * B-19 의 화이트리스트가 런타임 검증을 얹는다. <b>필드를 늘리면 그 선언을 함께 고치기 전까지
 * 테스트가 빨갛게 남는다</b> — 그것이 검증기의 목적이다.
 *
 * <p><b>I-7 — {@code SYSTEM} 과 {@code OUTPUT SPEC} 의 자리가 없다.</b> 작품이 채울 수 있는 것은
 * {@code worldPrompt} · {@code characters} 까지이며, 플랫폼 레이어는 {@code ai} 가 소유한다.
 * 작품 입력에 <b>"이전 지시를 무시하라"</b> 가 들어와도 그것은 WORLD 레이어의 본문일 뿐이다.
 *
 * <p><b>{@code stateVocabulary} 도 작품이 채우는 문장이 아니다</b> (§13-76). 여기로 건너가는 것은
 * {@code state_schema} 가 선언한 <b>이름 목록</b>이며, 그 이름을 무엇이라 소개할지는 {@code ai} 가
 * 정한다 — 작품이 넘길 수 있는 것은 목록의 <b>한 항목</b>뿐이라 지시문이 될 수 없다.
 *
 * @param worldPrompt 작품 세계관. 작품 레이어다 (R4.9 의 UGC 하드 제한 대상)
 * @param characters  등장인물 페르소나. <b>{@code display_order} 순</b>이다 (§4.4)
 * @param gameState   현재 GameState (§4.1). <b>서버가 만든 값</b>이며 AI 가 바꿀 수 없다 (I-9)
 * @param stateVocabulary {@code state_schema} 가 선언한 이름 (R4.1, §13-76). <b>값이 아니라 이름이다</b>
 * @param summary     오래된 턴의 압축 (R4.5). 없으면 {@code null}
 * @param recentTurns 최근 턴. <b>오래된 것이 앞</b>이며, 예산이 모자라면 앞에서부터 빠진다 (§4.4)
 * @param userAction  이번 턴에 고른 선택지의 본문. <b>서버가 저장해 둔 값이다</b> (I-1) —
 *                    클라이언트가 보낸 텍스트가 아니다. 첫 턴이면 {@code null}
 */
public record GenerationContext(
		String worldPrompt,
		List<Character> characters,
		JsonNode gameState,
		StateVocabulary stateVocabulary,
		String summary,
		List<RecentTurn> recentTurns,
		String userAction) {

	public GenerationContext {
		if (worldPrompt == null || worldPrompt.isBlank()) {
			throw new IllegalArgumentException("worldPrompt is required");
		}
		if (gameState == null) {
			throw new IllegalArgumentException("gameState is required");
		}
		// 빠뜨린 것을 빈 어휘로 봐주지 않는다. 그러면 프롬프트에서 이름이 조용히 사라지고,
		// 그 증상은 "플래그가 안 선다" 하나뿐이다 — §13-70~73 이 네 번 반복한 실패다.
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
	 * {@code state_schema} 가 선언한 이름 (R4.1, §13-76).
	 *
	 * <p><b>왜 값이 아니라 이름이 건너가는가.</b> {@code gameState} 는 <b>이미 선 값</b>만 담는다.
	 * 아직 서지 않은 플래그와 첫 델타를 받지 않은 수치는 거기 없고, 그러면 모델이 그 이름을 알
	 * 방법이 <b>추측뿐</b>이다 — 그리고 병합은 완전 일치로만 일어난다 (R4.1). 이름이 어긋나면
	 * {@link com.neowadaeum.play.engine.GameStateEngine} 이 조용히 버리고 그 이름을 가리키는
	 * 챕터·엔딩 조건은 <b>영원히 거짓</b>이 된다 (#367).
	 *
	 * <p><b>정렬해서 담는다.</b> {@code state_schema} 를 읽은 결과는 불변 컬렉션이고 그 순회 순서는
	 * JVM 마다 다르다 — 그대로 실으면 <b>같은 작품이 부팅마다 다른 프롬프트</b>를 갖게 되고,
	 * 골든 파일도 프롬프트 캐시도 그 위에 설 수 없다. 선언 순서는 이름 목록에서 의미가 없다.
	 *
	 * @param numerics  수치 경로. {@code <그룹>.<이름>} 이며 {@code stateChanges} 의 키와 같은 표기다
	 * @param flags     {@code flags.add} / {@code flags.remove} 가 쓸 수 있는 이름
	 * @param inventory {@code inventory.add} / {@code inventory.remove} 가 쓸 수 있는 이름
	 */
	public record StateVocabulary(List<String> numerics, List<String> flags, List<String> inventory) {

		public StateVocabulary {
			numerics = canonical(numerics);
			flags = canonical(flags);
			inventory = canonical(inventory);
		}

		/**
		 * 아무것도 선언되지 않은 작품 (R4.1).
		 *
		 * <p><b>{@code null} 을 허용하지 않기 위한 자리다.</b> 선언이 비었다는 것과 채우는 것을
		 * 잊었다는 것은 다른 사실이며, 둘을 같은 {@code null} 로 두면 배선 실수가 <b>"플래그가
		 * 안 선다"</b> 하나로만 나타난다.
		 */
		public static StateVocabulary none() {
			return new StateVocabulary(List.of(), List.of(), List.of());
		}

		private static List<String> canonical(List<String> names) {
			return names == null ? List.of() : names.stream().distinct().sorted().toList();
		}
	}

	/**
	 * 최근 턴 하나 (§5.1 의 RECENT TURNS, R4.7, §13-2).
	 *
	 * <p><b>원문과 압축본을 둘 다 나른다.</b> 어느 턴을 원문으로 싣고 어느 턴을 압축본으로 싣는지는
	 * <b>조립 시점의 예산 판단</b>이며(§13-2 의 {@code verbatim} 경계), 그 판단은 {@code ai} 가 한다.
	 * 여기서 미리 하나로 정하면 예산 정책을 바꿀 때 두 모듈을 함께 고쳐야 한다.
	 *
	 * <p><b>{@code paragraphsDigest} 는 아직 만들어지지 않는다.</b> 압축은 요약 파이프라인(B-34)의
	 * 일이고 그것이 없는 지금은 {@code null} 이다. 그때 조립기는 원문을 쓰며, 예산이 모자라면
	 * <b>오래된 턴부터 통째로 빠진다</b> (§4.4) — 조용히 잘라 내지 않는다.
	 *
	 * @param turnNo           턴 번호
	 * @param chosenChoiceText 그 턴에서 고른 선택지 본문. 아직 고르지 않았으면 {@code null}
	 * @param paragraphs       본문 원문
	 * @param paragraphsDigest 압축본. 아직 없으면 {@code null} (B-34)
	 */
	public record RecentTurn(int turnNo, String chosenChoiceText, String paragraphs, String paragraphsDigest) {

		public RecentTurn {
			if (turnNo < 1) {
				throw new IllegalArgumentException("turnNo starts at 1");
			}
			if (paragraphs == null || paragraphs.isBlank()) {
				throw new IllegalArgumentException("recent turn paragraphs are required");
			}
		}
	}
}
