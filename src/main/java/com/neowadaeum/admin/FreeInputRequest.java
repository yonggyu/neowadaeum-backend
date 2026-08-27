package com.neowadaeum.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자가 넣는 행동 문장 (§14).
 *
 * <p><b>길이를 제한한다.</b> 선택지 자리에 들어가는 한 문장이며, 길어질수록 프롬프트에서
 * 차지하는 몫이 커져 <b>세계관과 최근 턴을 밀어낸다.</b>
 *
 * @param action 형식만 본다. 내용 판정은 L1 이 한다 (I-17)
 */
public record FreeInputRequest(@NotBlank @Size(max = 200) String action) {
}
