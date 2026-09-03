package com.neowadaeum.authoring.api;

import com.neowadaeum.authoring.metadata.AuthoringMetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 만들기 메타데이터 (§13-56, 이슈 #282 · #315).
 *
 * <p><b>결정은 있는데 전달 경로가 없었다.</b> 장르 다섯(§13-25)과 조건 템플릿 넷(§13-35)은
 * 정해져 있었지만 어느 오퍼레이션도 그것을 내려주지 않아, 프론트가 키와 한국어 라벨을
 * <b>소스에 상수로</b> 들고 있었다. 목록이 바뀌는 날부터 옛 목록을 보여 주고 서버가 거부할
 * 때까지 아무도 모른다.
 *
 * <p><b>둘을 한 경로로 묶는다.</b> 같은 화면(작품 만들기)이 같은 시점에 둘 다 필요로 하며,
 * 나누면 화면 하나가 왕복을 둘 한다. 새 항목이 생겨도 경로가 늘지 않는다.
 *
 * <p><b>인증이 필요하다.</b> 작성자 경로이며, 익명에게 열 이유가 없다 — {@code /api/v1/consents}
 * 가 열려 있는 것은 <b>가입 전에</b> 불리기 때문이고 이 경로는 그렇지 않다.
 *
 * <p><b>회원에 관한 값이 없다.</b> 응답은 누가 부르든 같으므로 {@code playerRef} 를 읽지 않는다
 * (S-9).
 */
@RestController
@RequestMapping("/api/v1/authoring/metadata")
public class AuthoringMetadataController {

	private final AuthoringMetadataService metadata;

	public AuthoringMetadataController(AuthoringMetadataService metadata) {
		this.metadata = metadata;
	}

	@GetMapping
	public AuthoringMetadataResponse read() {
		return AuthoringMetadataResponse.of(this.metadata.read());
	}
}
