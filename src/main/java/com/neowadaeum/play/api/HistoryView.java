package com.neowadaeum.play.api;

import java.util.List;

/**
 * 기록 한 쪽 (§13.6 의 {@code HistoryResponse}, 화면 2e).
 *
 * <p><b>{@code choiceId} 가 없다.</b> 기록은 읽기 전용이며, 여기서 받은 식별자로 턴을 진행할 수
 * 있으면 <b>지나간 분기를 다시 고를 수 있게 된다</b> — I-1 이 서버 발급 식별자를 요구하는 이유가
 * 그것이고, 형식에 턴 번호가 들어 있어 재사용이 막히지만 <b>애초에 주지 않는 편이 낫다.</b>
 *
 * @param nextCursor 다음 쪽의 시작점. 없으면 끝이다
 * @param noticeText AI 사전 고지 문구. <b>코드에 없다</b> — {@code service_config} 에서 온다
 *                   (R11.1). 이 화면의 Footer 도 문구를 상시 표시한다 (#281)
 */
public record HistoryView(List<Item> items, String nextCursor, boolean hasMore,
		String noticeText) {

	public HistoryView {
		items = List.copyOf(items == null ? List.of() : items);
	}

	/**
	 * 지나간 턴 하나 (§13.6 의 {@code HistoryItem}).
	 *
	 * @param chosenChoiceText 그때 고른 선택지의 <b>문구</b>. 식별자가 아니다
	 * @param isPending        마지막 턴이며 아직 선택이 이뤄지지 않았다 (§13-9)
	 */
	public record Item(int turnNo, int chapterNo, String chapterTitle, String speakerName,
			List<TurnView.Paragraph> paragraphs, String chosenChoiceText, boolean isPending) {

		public Item {
			paragraphs = List.copyOf(paragraphs == null ? List.of() : paragraphs);
		}
	}
}
