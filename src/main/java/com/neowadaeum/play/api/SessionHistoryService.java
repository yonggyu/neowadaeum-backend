package com.neowadaeum.play.api;

import com.neowadaeum.catalog.query.StoryVersionFacade;
import com.neowadaeum.catalog.query.StoryVersionView;
import com.neowadaeum.common.error.ApiException;
import com.neowadaeum.common.error.ErrorCode;
import com.neowadaeum.play.domain.PlaySession;
import com.neowadaeum.play.domain.Turn;
import com.neowadaeum.play.repository.PlaySessionRepository;
import com.neowadaeum.play.repository.TurnRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지나간 턴 조회 (§13.6, 화면 2e, B-35).
 *
 * <p><b>역순이다.</b> 화면이 "위로 스크롤해 더 읽기"이므로 커서는 <b>지금 보고 있는 것보다
 * 과거</b>를 가리킨다. 턴 번호가 세션 안에서 유일하므로(I-6) 커서가 곧 턴 번호이고,
 * 같은 값이 둘일 수 없어 쪽 경계에서 중복이나 누락이 생기지 않는다.
 *
 * <p><b>{@code choiceId} 를 내보내지 않는다.</b> 저장된 선택지 JSON 에서 <b>문구만</b> 꺼낸다.
 */
@Service
public class SessionHistoryService {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int DEFAULT_LIMIT = 20;

	private static final int MAX_LIMIT = 50;

	private final PlaySessionRepository sessions;

	private final TurnRepository turns;

	private final StoryVersionFacade storyVersions;

	public SessionHistoryService(PlaySessionRepository sessions, TurnRepository turns,
			StoryVersionFacade storyVersions) {
		this.sessions = sessions;
		this.turns = turns;
		this.storyVersions = storyVersions;
	}

	/**
	 * @param cursor 이전 쪽의 {@code nextCursor}. 처음이면 {@code null}
	 * @throws ApiException {@code NOT_FOUND} — 없거나 <b>남의</b> 세션 (I-3)
	 */
	public HistoryView history(UUID playerRef, UUID sessionId, String cursor, Integer limit) {
		PlaySession session = requireOwned(playerRef, sessionId);
		int size = Math.clamp((limit != null) ? limit : DEFAULT_LIMIT, 1, MAX_LIMIT);

		// 한 개 더 읽어 "더 있는가"를 별도 count 없이 안다.
		List<Turn> rows = this.turns.findBySessionIdAndTurnNoLessThanAndDeletedAtIsNullOrderByTurnNoDesc(
				sessionId, cursorOf(cursor), Limit.of(size + 1));
		boolean more = rows.size() > size;
		List<Turn> page = more ? rows.subList(0, size) : rows;

		StoryVersionView version = this.storyVersions.findByVersionId(session.getStoryVersionId())
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		int lastTurnNo = session.getTurnNo();

		List<HistoryView.Item> items = new ArrayList<>(page.size());
		for (Turn turn : page) {
			items.add(new HistoryView.Item(turn.getTurnNo(), turn.getChapterNo(),
					chapterTitle(version, turn.getChapterNo()), turn.getSpeakerName(),
					paragraphsOf(turn), chosenTextOf(turn), isPending(turn, lastTurnNo)));
		}
		return new HistoryView(items, more ? String.valueOf(page.getLast().getTurnNo()) : null, more);
	}

	/**
	 * <b>§13-9 의 {@code isPending} 정의</b> — 마지막 턴이며 아직 선택이 이뤄지지 않았다.
	 *
	 * <p>둘 다여야 한다. "선택이 없다"만 보면 되돌린 뒤의 과거 턴도 pending 이 되고,
	 * "마지막이다"만 보면 이미 고른 마지막 턴까지 pending 이 된다.
	 */
	private static boolean isPending(Turn turn, int lastTurnNo) {
		return turn.getTurnNo() == lastTurnNo && turn.getChosenChoiceId() == null;
	}

	/**
	 * 커서는 턴 번호다. 처음이면 <b>마지막 턴보다 하나 뒤</b>에서 시작한다.
	 *
	 * <p>해석되지 않는 값은 처음으로 본다 — 500 을 내면 커서 형식을 바꾸는 순간 진행 중인
	 * 클라이언트가 전부 깨진다 (§13-25 와 같은 판단).
	 */
	private static int cursorOf(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return Integer.MAX_VALUE;
		}
		try {
			return Integer.parseInt(cursor.trim());
		}
		catch (NumberFormatException ex) {
			return Integer.MAX_VALUE;
		}
	}

	/**
	 * 고른 선택지의 <b>문구</b>를 저장된 배열에서 꺼낸다.
	 *
	 * <p>식별자로 찾되 식별자를 내보내지 않는다 — 그것이 이 메서드가 존재하는 이유다.
	 */
	private static String chosenTextOf(Turn turn) {
		if (turn.getChosenChoiceId() == null) {
			return null;
		}
		for (JsonNode choice : JSON.readTree(turn.getChoices())) {
			if (turn.getChosenChoiceId().equals(choice.path("choiceId").asString(null))) {
				return choice.path("text").asString(null);
			}
		}
		return null;
	}

	/** 저장은 열거형 이름이고 응답 계약은 §5.2 표기다 — 턴 응답과 같은 규칙이다. */
	private static List<TurnView.Paragraph> paragraphsOf(Turn turn) {
		List<TurnView.Paragraph> paragraphs = new ArrayList<>();
		JSON.readTree(turn.getParagraphs()).forEach(node -> paragraphs.add(new TurnView.Paragraph(
				node.path("type").asString("NARRATION").toLowerCase(Locale.ROOT),
				node.path("speakerName").asString(null),
				node.path("text").asString(null))));
		return paragraphs;
	}

	private static String chapterTitle(StoryVersionView version, int chapterNo) {
		return version.chapters().stream()
				.filter(chapter -> chapter.chapterNo() == chapterNo)
				.map(StoryVersionView.ChapterView::title)
				.findFirst()
				.orElse(null);
	}

	/** <b>남의 세션은 없는 것과 구분되지 않는다</b> (I-3). */
	private PlaySession requireOwned(UUID playerRef, UUID sessionId) {
		PlaySession session = this.sessions.findById(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!session.getPlayerRef().equals(playerRef)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return session;
	}
}
