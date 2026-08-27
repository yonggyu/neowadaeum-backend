package com.neowadaeum.identity.api;

import jakarta.validation.constraints.Pattern;

/**
 * 인증기가 보여 주는 여섯 자리.
 *
 * @param code 자리 수와 문자 종류만 본다. <b>맞는지는 여기서 판단하지 않는다</b> — 형식으로
 *     걸러 낼 수 있는 것과 비밀을 알아야 판단할 수 있는 것을 한 곳에서 섞지 않는다
 */
public record TotpCodeRequest(@Pattern(regexp = "\\d{6}") String code) {
}
