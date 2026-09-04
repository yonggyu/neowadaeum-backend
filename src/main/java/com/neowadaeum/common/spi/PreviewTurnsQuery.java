package com.neowadaeum.common.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 미리보기 세션이 실제로 내놓은 문장 (§13-61, §13-68, #332).
 *
 * <p><b>검수자가 프롬프트만 읽고 판정하지 않게 하는 값이다.</b> 와이어프레임 {@code 3h} 의
 * 검수 상세는 미리보기 3턴을 보여 준다 — <i>"이 작품이 실제로 어떤 문장을 내놓는가"</i> 를
 * 보는 자리이고, 그것 없이 내리는 승인은 작품의 절반만 본 승인이다.
 *
 * <p><b>왜 SPI 인가.</b> 턴은 {@code play} 스토어의 것이고 {@code authoring} 은 {@code play} 를
 * 알지 못한다 — {@link TestSessionStarter} 가 세션을 여는 데 쓴 것과 같은 경계, 반대 방향이다.
 *
 * <p><b>가공하지 않는다.</b> 본문과 선택지는 저장된 JSON 그대로 온다 — 검수는 <b>독자가 볼
 * 것</b>을 보는 자리이고, 다시 만들면 그것이 실제로 나간 문장인지 알 수 없게 된다.
 */
public interface PreviewTurnsQuery {

	/**
	 * @param sessionId 원고가 기억하는 미리보기 세션. <b>없으면 빈 목록</b> — 파기되었거나
	 *     (§13-37) 미리보기를 돌린 적이 없다. 어느 쪽이든 <b>지어내지 않는다</b>
	 * @return 턴 오름차순. 읽는 순서가 곧 이야기 순서다
	 */
	List<PreviewTurn> findBySession(UUID sessionId);

	/**
	 * 미리보기 턴 한 건.
	 *
	 * @param paragraphs 본문 문단 배열의 JSON 원문 (R5.1)
	 * @param choices 그 턴이 발급한 선택지 배열의 JSON 원문. {@code choiceId} 는 서버가 발급한
	 *     값이다 (I-1)
	 */
	record PreviewTurn(int turnNo, int chapterNo, String speakerName, String paragraphs,
			String choices, Instant createdAt) {
	}
}
