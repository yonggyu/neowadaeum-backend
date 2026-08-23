package com.neowadaeum.common.web;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code dev} 전용 고정 {@code player_ref} — <b>인증 우회다</b> (ADR-0004 대체 수단 1).
 *
 * <p><b>이것은 편의가 아니다.</b> ADR-0004 는 이것을 *"편의가 아니라 인증 우회이며, B-47 이
 * {@code prod} 에서 404 여야 하는 것과 같은 이유이고 그보다 위험하다"* 고 규정한다. 슬라이스에서
 * 인증(B-07 · B-12)을 제외한 대가로 들어온 구멍이며, <b>복귀 조건은 B-12 착수 시 제거</b>다 (#34).
 *
 * <p><b>{@code @Profile("dev & !prod")} 다 — {@code @Profile("!prod")} 가 아니다.</b> 후자는 <b>프로파일이
 * 하나도 활성화되지 않은 상태에서도 참</b>이라서, 프로파일 지정을 빠뜨린 배포에서 우회가 조용히
 * 살아난다. 같은 함정을 {@code FixedStoryProvider} 에서 이미 확인했다(#47). 인증에서 그 실수는
 * 훨씬 비싸므로 <b>명시적으로 켤 때만 존재</b>하게 한다.
 *
 * <p><b>{@code "dev & !prod"} 인 이유.</b> {@code "dev"} 만 쓰면 두 프로파일이 함께 켜진 배포에서
 * 우회가 살아난다. 그런 조합은 실수로 만들어지며, 인증에서 그 실수는 되돌릴 수 없다 —
 * {@code prod} 가 켜져 있으면 <b>무슨 일이 있어도</b> 이 빈은 존재하지 않는다.
 *
 * <p>값을 설정으로 받지 않는다. 설정에서 온 값은 운영에서도 채워질 수 있고, 그러면 이 빈이
 * "설정만 맞추면 어디서나 도는 것"이 된다.
 */
@Component
@Profile("dev & !prod")
public class DevFixedPlayerRefResolver implements PlayerRefResolver {

	/** 고정 값. {@code dev} 에서만 존재하므로 공개되어도 인증 자산이 아니다. */
	private static final UUID DEV_PLAYER_REF = UUID.fromString("00000000-0000-4000-8000-000000000001");

	@Override
	public UUID currentPlayerRef() {
		return DEV_PLAYER_REF;
	}
}
