package com.neowadaeum.identity.api;

import jakarta.validation.constraints.NotBlank;

/** 재발급 요청 (§13-22 의 {@code RefreshRequest}). */
public record RefreshRequest(@NotBlank String refreshToken) {
}
