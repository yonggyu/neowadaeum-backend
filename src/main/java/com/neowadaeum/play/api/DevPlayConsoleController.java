package com.neowadaeum.play.api;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev 플레이 콘솔 (S-10, B-47) — 시드 작품을 브라우저에서 완주해 보는 <b>임시 검증 UI</b>.
 *
 * <p><b>{@code prod} 에서는 이 빈이 없다.</b> 매핑 자체가 존재하지 않으므로 경로는 404 이고,
 * 콘솔의 존재가 드러나지 않는다 (B-47 DoD). 프로파일 표현식은 {@code dev & !prod} — 인증 우회
 * 리졸버와 같은 이유다: {@code !prod} 는 무프로파일 배포에서 참이 되고, {@code dev} 만 쓰면 두
 * 프로파일이 함께 켜진 배포에서 열린다 (#47, #34 에서 확인한 함정).
 *
 * <p><b>HTML 을 {@code static/} 에 두지 않는다.</b> 자동 서빙 경로는 프로파일과 무관하게 노출되어
 * 이 프로파일 게이트를 통째로 우회한다. 컨트롤러가 classpath 에서 직접 읽는 이유다.
 *
 * <p>콘솔은 클라이언트일 뿐이다 — 판정·검증은 전부 서버에 있다(S-5~S-9). 프론트 레포와 무관하다.
 */
@RestController
@Profile("dev & !prod")
public class DevPlayConsoleController {

	private static final Resource CONSOLE_PAGE = new ClassPathResource("devconsole/play-console.html");

	@GetMapping(value = "/dev/console", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<Resource> console() {
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
				.body(CONSOLE_PAGE);
	}
}
