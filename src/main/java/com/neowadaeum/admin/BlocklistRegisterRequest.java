package com.neowadaeum.admin;

import com.neowadaeum.authoring.blocklist.BlocklistKind;
import com.neowadaeum.authoring.blocklist.BlocklistSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 등록 요청 (§14).
 *
 * <p><b>정규화 값을 받지 않는다</b> (R2.5). 클라이언트가 보낸 정규화 값을 믿으면, 그 값을 비워
 * 보내는 것만으로 <b>걸리지 않는 항목</b>을 등록할 수 있다 — 등록했다고 믿게 만드는 가장
 * 조용한 방법이다.
 *
 * @param value 사람이 읽는 값. 서버가 여기서 정규화 값을 만든다
 * @param source 어디서 왔는가. 사후에 근거를 되짚는 데 쓴다
 */
public record BlocklistRegisterRequest(@NotNull BlocklistKind kind, @NotBlank @Size(max = 200) String value,
		@NotNull BlocklistSeverity severity, @Size(max = 200) String source) {
}
