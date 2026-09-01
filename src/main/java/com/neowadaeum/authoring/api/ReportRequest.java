package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.report.ReportReason;
import com.neowadaeum.authoring.report.ReportTarget;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 신고 (§13.9).
 *
 * <p><b>{@code sessionId} · {@code turnNo} 는 턴 신고에만 있다.</b> 그 턴이 어느 플레이에서
 *나왔는지가 있어야 재현되며, 작품 신고에는 그런 것이 없다.
 *
 * <p><b>{@code detail} 은 되돌려주지 않는다.</b> 신고 내용이 작성자에게 가면 신고자가
 * 특정된다.
 */
public record ReportRequest(@NotNull ReportTarget targetType, @NotNull UUID targetId,
		UUID sessionId, @Positive Integer turnNo, @NotNull ReportReason reason,
		@Size(max = 1000) String detail) {
}
