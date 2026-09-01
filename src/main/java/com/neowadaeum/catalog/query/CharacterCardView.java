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
 */
public record CharacterCardView(UUID characterId, String name, String role, String portraitImage,
		String oneLine) {
}
