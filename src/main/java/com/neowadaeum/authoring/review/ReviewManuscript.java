package com.neowadaeum.authoring.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 검수자가 보고 판정하는 것 (#316, §13-61).
 *
 * <p><b>큐가 답하지 않는 것을 답한다.</b> {@code ReviewQueueItem} 은 <b>무엇을 볼 차례인가</b>를
 * 답하고 원고 본문을 담지 않는다 — 그 문이 없으면 검수자는 <b>제목과 상태만 보고 승인/반려</b>하게
 * 되며, 그것은 검수가 아니다.
 *
 * <p><b>{@code playerRef} 가 없다.</b> 작성자는 표시명으로만 온다 (§13-7, I-3) — 식별자를 함께
 * 내보내면 그 값이 관리자 화면의 로그로 퍼진다.
 *
 * <p><b>미리보기 3턴이 있다</b> (#332, §13-68). 미리보기는 여전히 <b>다른 작품</b>을 만들지만
 * (§13-5), 이제 원고가 그 세션을 기억하므로 <b>원고를 거쳐</b> 갈 수 있다. 검수자가
 * <i>"이 작품이 실제로 어떤 문장을 내놓는가"</i> 를 보는 자리이고, 그것 없이 내리는 승인은
 * 프롬프트만 읽고 내린 승인이다.
 *
 * <p><b>비어 있을 수 있다.</b> 작성자가 미리보기를 돌리지 않았거나, 돌린 것이 보관 기간을
 * 넘겨 파기되었다 (§13-37) — 어느 쪽이든 <b>없다는 것이 사실</b>이며 지어내지 않는다.
 * {@code previewedAt} 이 <b>얼마나 오래된 미리보기인지</b>를 함께 말한다: 오래된 미리보기는
 * 지금 원고와 다른 문장을 보여 줄 수 있고, 검수자가 그것을 알아야 한다.
 *
 * @param submittedAt 작품이 만들어진 시각. 제출 회차별 시각은 검수 이력이 답한다 (§13-57)
 * @param worldPrompt 세계관 프롬프트 원문. <b>UGC 원고의 본체다</b>
 * @param autoCheck 자동 검수가 무엇을 봤는지. 자동 검수 기록이 없으면 {@code null}
 * @param previewedAt 마지막 미리보기 시각. 미리보기가 없으면 {@code null}
 * @param previewTurns 그 미리보기가 실제로 내놓은 턴. 없으면 빈 목록
 */
public record ReviewManuscript(UUID storyId, String title, String shortDesc, String worldIntro,
		String reviewStatus, String visibility, Instant submittedAt, String authorDisplayName,
		String worldPrompt, List<ManuscriptCharacter> characters, List<ManuscriptChapter> chapters,
		List<ManuscriptEnding> endings, AutoCheckSummary autoCheck, Instant previewedAt,
		List<PreviewTurn> previewTurns) {

	public ReviewManuscript {
		characters = List.copyOf(characters == null ? List.of() : characters);
		chapters = List.copyOf(chapters == null ? List.of() : chapters);
		endings = List.copyOf(endings == null ? List.of() : endings);
		previewTurns = List.copyOf(previewTurns == null ? List.of() : previewTurns);
	}

	/**
	 * 미리보기 턴 한 건 (#332).
	 *
	 * <p><b>가공하지 않는다.</b> 본문과 선택지는 저장된 JSON 원문 그대로다 — 검수는 <b>독자가
	 * 볼 것</b>을 보는 자리이고, 다시 만들면 그것이 실제로 나간 문장인지 알 수 없게 된다.
	 */
	public record PreviewTurn(int turnNo, int chapterNo, String speakerName, String paragraphs,
			String choices, Instant createdAt) {
	}

	/**
	 * 등장인물 한 명.
	 *
	 * <p><b>{@code persona} 를 담는다.</b> 상세 화면은 그것을 감추지만({@code CharacterCardView})
	 * 검수는 반대다 — 페르소나 프롬프트야말로 <b>매 턴 모델에게 들어가는 문장</b>이고, 그것을
	 * 보지 않은 승인은 작품의 절반만 본 승인이다.
	 */
	public record ManuscriptCharacter(String name, String persona) {
	}

	/** 챕터 한 장. <b>진입 조건식은 담지 않는다</b> — 판정 로직이지 사람이 읽는 문장이 아니다. */
	public record ManuscriptChapter(int chapterNo, String title, int minTurns, int maxTurns) {
	}

	/**
	 * 엔딩 하나.
	 *
	 * <p><b>에필로그 원문을 담는다.</b> 독자가 마지막에 읽는 작성자의 문장이므로 판정 대상이다.
	 *
	 * @param secret 숨은 엔딩인가 (R7.11)
	 * @param defaultEnding 어떤 조건에도 걸리지 않았을 때의 폴백인가 (R2.2)
	 */
	public record ManuscriptEnding(int endingNo, String label, String epilogueText, boolean secret,
			boolean defaultEnding) {
	}

	/**
	 * 자동 검수 요약 (R8.5, R8.7).
	 *
	 * <p><b>카테고리만이다.</b> 어떤 항목에 걸렸는지를 담으면 이 응답이 우회 사전이 된다 (S-11).
	 *
	 * @param verdict 자동 단계의 판정. {@code hold} 는 <b>사람이 봐야 한다</b>는 표식이다 (§13-42)
	 */
	public record AutoCheckSummary(String verdict, List<String> reasons, Instant checkedAt) {

		public AutoCheckSummary {
			reasons = List.copyOf(reasons == null ? List.of() : reasons);
		}
	}
}
