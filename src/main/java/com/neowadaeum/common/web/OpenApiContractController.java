package com.neowadaeum.common.web;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 계약 문서 서빙 (B-06) — <b>수기 계약 파일 그대로</b>.
 *
 * <p>{@code docs/openapi.yaml} 이 런타임 진실의 원천이다 (CLAUDE.md Source of Truth). 그래서
 * springdoc 의 <b>런타임 자동 생성본을 서빙하지 않는다</b> — 생성본은 구현의 사후 기록이고,
 * 그것을 문서로 내보내는 순간 계약이 둘이 된다. springdoc 은 이 파일을 보여 주는 <b>UI 로만</b> 쓴다
 * ({@code springdoc.swagger-ui.url}).
 *
 * <p><b>{@code prod} 에서는 이 빈이 없다.</b> 매핑이 없으므로 경로는 404 다. 프로파일 표현식은
 * {@code dev & !prod} — dev 콘솔(B-47)·인증 우회 리졸버(#34)와 같은 표현식이다. {@code "!prod"} 만
 * 쓰면 프로파일 미지정 배포에서 열리고, {@code "dev"} 만 쓰면 둘이 함께 켜진 조합에서 열린다.
 *
 * <p>파일 자체에는 비밀이 없다 — 같은 내용이 공개 레포에 커밋되어 있다 (S-11 을 지켜 작성했으므로
 * 운영 도메인·검수 임계값이 들어 있지 않다). 그럼에도 닫는 이유는 <b>계약 문서가 운영 표면이
 * 아니기 때문</b>이다. 열어 둘 이유가 없는 경로는 열지 않는다.
 */
@RestController
@Profile("dev & !prod")
public class OpenApiContractController {

	/**
	 * 빌드가 {@code docs/openapi.yaml} 을 여기로 복사한다 (build.gradle.kts).
	 *
	 * <p>{@code static/} · {@code public/} 아래가 <b>아니다</b> — 거기 두면 프로파일과 무관하게
	 * 서빙되어 위의 게이트가 무의미해진다.
	 */
	private static final Resource CONTRACT = new ClassPathResource("openapi/openapi.yaml");

	@GetMapping(value = "/openapi.yaml", produces = "application/yaml")
	public ResponseEntity<Resource> contract() {
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("application/yaml;charset=UTF-8"))
				.body(CONTRACT);
	}
}
