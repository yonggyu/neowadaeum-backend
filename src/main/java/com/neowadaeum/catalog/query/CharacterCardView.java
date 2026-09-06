package com.neowadaeum.catalog.query;

import java.util.UUID;

/**
 * 상세 화면의 등장인물 카드 (§13.3 의 {@code CharacterCard}).
 *
 * <p><b>{@code personaPrompt} 가 없다.</b> 그것은 프롬프트의 재료이지 화면의 것이 아니다 —
 * 내보내면 작품의 프롬프트가 그대로 공개된다.
 *
 * <p>{@code is_visible_in_detail = false} 인 인물은 애초에 오지 않는다. 후반에 등장하는 인물을
 * 상세 화면이 미리 보여 주면 그 자체가 스포일러다.
 *
 * <p><b>{@code portraitImage} 는 화면이 {@code <img src>} 에 그대로 넣는 값이다</b> (#378,
 * §13-79). UGC 작품의 {@code portrait_image_key} 에는 <b>객체 키</b>가 들어 있으므로 (#315) 그대로
 * 내보내면 초상이 전부 깨진다 — 커버와 <b>같은 판정</b>을 지나며, 승인된 UGC 만 서명된다.
 * <b>이 값을 담은 응답을 캐시하지 않는다</b> — URL 이 응답보다 먼저 만료된다.
 *
 * @param portraitImage 초상을 올리지 않은 인물이 정상이다 — 그때 {@code null} 이다
 */
public record CharacterCardView(UUID characterId, String name, String role, String portraitImage,
		String oneLine) {
}
