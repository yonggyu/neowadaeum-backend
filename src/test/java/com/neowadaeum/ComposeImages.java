package com.neowadaeum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code docker-compose.yml} 의 이미지 태그를 테스트가 그대로 읽는다.
 *
 * <p>테스트 컨테이너와 로컬 컨테이너의 이미지가 다르면 검증 자체가 의미를 잃는다. 스키마 초기화 스크립트의
 * 동작도, 권한 기본값도 메이저 버전에 묶여 있기 때문이다. 태그를 테스트 코드에 한 번 더 적으면 반드시
 * 어긋나므로(실제로 {@code postgres:latest} 로 어긋나 있었다) 출처를 한 곳으로 둔다.
 *
 * <p>YAML 파서를 끌어오지 않는다. 읽는 대상이 {@code services.<이름>.image} 한 줄뿐이고, 형식이 바뀌면
 * 조용히 넘어가는 대신 예외로 드러난다.
 */
final class ComposeImages {

	private static final Path COMPOSE_FILE = Path.of("docker-compose.yml");

	/** 두 칸 들여쓴 서비스 이름. {@code volumes:} 같은 최상위 키(0칸)와 구분된다. */
	private static final Pattern SERVICE = Pattern.compile("^ {2}(\\S+):\\s*$");

	private static final Pattern IMAGE = Pattern.compile("^\\s+image:\\s*(\\S+)\\s*$");

	private ComposeImages() {
	}

	static String of(String service) {
		boolean inTargetService = false;
		for (String line : lines()) {
			Matcher serviceMatcher = SERVICE.matcher(line);
			if (serviceMatcher.matches()) {
				inTargetService = serviceMatcher.group(1).equals(service);
				continue;
			}
			Matcher imageMatcher = IMAGE.matcher(line);
			if (inTargetService && imageMatcher.matches()) {
				return imageMatcher.group(1);
			}
		}
		throw new IllegalStateException(
				"%s 에서 서비스 '%s' 의 image 를 찾지 못했다.".formatted(COMPOSE_FILE, service));
	}

	private static List<String> lines() {
		try {
			return Files.readAllLines(COMPOSE_FILE);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(
					"%s 를 읽지 못했다. 테스트는 레포 루트에서 실행되어야 한다.".formatted(COMPOSE_FILE), ex);
		}
	}
}
