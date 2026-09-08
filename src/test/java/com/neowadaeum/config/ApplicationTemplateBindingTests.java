package com.neowadaeum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

/**
 * §7.3 — {@code application.yml.template} 이 알려 주는 자리가 <b>실제로 바인딩되는 자리</b>인지
 * 본다 (#419, #248).
 *
 * <p>템플릿은 값을 넣는 사람에게 유일한 안내다. 그런데 그 안내가 틀려도 아무것도 실패하지 않는다 —
 * 접두사 밖에 놓인 키는 오류가 아니라 <b>아무도 읽지 않는 프로퍼티</b>로 조용히 남고, relaxed
 * binding 은 그 차이를 메우지 못한다(흡수하는 것은 이름의 <i>표기</i>이지 접두사의 <i>위치</i>가
 * 아니다). #419 가 정확히 그 자리였다: 루트 {@code cors:} 는 {@code app.cors} 와 다른 키였고,
 * 템플릿을 그대로 복사한 배포는 값을 넣고도 같은 실패로 되돌아갔다.
 *
 * <p><b>통합 테스트가 이것을 잡을 수 없다.</b> 테스트는 {@code app.cors.allowed-origins} 를
 * {@code DynamicPropertyRegistrar} 로 <b>직접 주입한다</b> — §7.3(런타임 값에 기본값 금지)과
 * S-11(운영 오리진 비기재) 때문에 그래야 한다. 즉 컨텍스트는 템플릿이 정의한 키를 한 번도 쓰지
 * 않으므로, 템플릿이 그 값을 어느 자리에 두든 초록이다. 그래서 <b>템플릿 자체를 읽는</b> 자리를
 * 따로 둔다 — {@link AutoConfigurationExclusionTests} 가 "파일에 적혀 있다는 것은 아무것도
 * 보장하지 않는다"(§2.2)를 동작으로 확인하는 것과 같은 성질의 대비다.
 *
 * <p><b>접두사를 하드코딩하지 않는다.</b> 손으로 적은 목록은 프로퍼티가 하나 늘 때마다 낡고,
 * 낡은 목록을 사람이 대조하는 것이 #419 를 만든 방식이다. 여기서는 {@code com.neowadaeum} 을
 * 스캔해 {@link ConfigurationProperties} 접두사를 그때그때 읽는다.
 *
 * <p><b>같은 성질의 두 번째 실패가 #428 이다.</b> 자리는 맞아도 값이 도착하지 않을 수 있다 —
 * 템플릿에 {@code spring.config.import} 가 없으면 {@code .env} 가 로딩되지 않아 모든
 * {@code ${VAR}} 가 리터럴로 남고, 그 실패는 "시크릿이 짧다"로 나타나 원인을 가리지 않는다.
 * 그리고 그 한 줄은 <b>개발자의 로컬 사본에만</b> 3개월을 살아 있었다 — {@code application.yml} 은
 * 추적되지 않아 앞서 나가도 diff 가 보이지 않기 때문이다. 그래서 이 클래스가 보는 것은 셋이다:
 * 키의 <b>위치</b>(#419) · 값의 <b>도착</b>(#428) · 로컬 사본의 <b>표류</b>(#428).
 *
 * <p>컨테이너를 쓰지 않는다 — 파일과 클래스패스만 본다. 빠른 루프(`./gradlew test`)에서 매 PR 마다
 * 돈다.
 */
class ApplicationTemplateBindingTests {

	private static final String TEMPLATE = "/application.yml.template";

	/**
	 * 템플릿을 복사해 만든 로컬 설정. <b>추적되지 않으므로 없을 수 있다</b> — CI 가 그렇다. 있을
	 * 때만 대조하고, 대조하는 것은 <b>키뿐이다</b> (S-11).
	 */
	private static final String LOCAL_COPY = "/application.yml";

	private static final String BASE_PACKAGE = "com.neowadaeum";

	/**
	 * 프레임워크가 소유한 최상위 네임스페이스. <b>이 넷만 하드코딩한다</b> — 우리가 정의하는 이름이
	 * 아니라 Spring 이 정의하는 이름이라, 우리 쪽 프로퍼티가 늘어도 이 집합은 늘지 않는다. 반대로
	 * 우리 네임스페이스를 여기 적기 시작하면 이 검사는 자기가 지키려던 것을 지키지 못한다.
	 */
	/**
	 * 프레임워크가 소유한 최상위 네임스페이스.
	 *
	 * <p><b>{@code server} 는 #458 에서 늘었다</b> — 전달 헤더 전략과 Tomcat {@code RemoteIpValve}
	 * 의 신뢰 목록이 그 아래 산다 (§13-93). 이 목록에 더하는 것은 <b>우리가 바인딩하지 않는
	 * 키를 허용한다</b>는 뜻이므로, 프레임워크가 실제로 읽는 이름만 넣는다.
	 */
	private static final Set<String> FRAMEWORK_ROOTS =
			Set.of("spring", "management", "logging", "springdoc", "server");

	/** 문서 안에서 들여쓰기 없이 시작하는 키. 주석(`#`)과 리스트 항목(`-`)은 걸리지 않는다. */
	private static final Pattern ROOT_KEY = Pattern.compile("^([A-Za-z][A-Za-z0-9_.-]*):(\\s.*)?$");

	/**
	 * §7.3 — 템플릿의 모든 <b>최상위 키</b>는 프레임워크 네임스페이스이거나, 우리가 실제로
	 * 바인딩하는 접두사의 첫 마디여야 한다.
	 *
	 * <p><b>#419 를 잡는 자리가 여기다.</b> 루트 {@code cors:} 는 어느 쪽에도 속하지 않는다.
	 */
	@Test
	void S7_3_every_root_key_is_a_framework_namespace_or_a_bound_prefix() {
		Set<String> boundRoots = new TreeSet<>();
		boundPrefixes().forEach(prefix -> boundRoots.add(prefix.split("\\.", 2)[0]));

		List<String> orphans = new ArrayList<>();
		for (Map<String, Object> document : documents(TEMPLATE)) {
			for (String root : document.keySet()) {
				String key = canonical(root);
				if (!FRAMEWORK_ROOTS.contains(key) && !boundRoots.contains(key)) {
					orphans.add(root);
				}
			}
		}

		assertThat(orphans)
				.as("""
						application.yml.template 의 최상위 키 %s 를 읽는 곳이 없다.

						최상위 키는 (1) 프레임워크 네임스페이스 %s 이거나 (2) @ConfigurationProperties \
						접두사의 첫 마디 %s 여야 한다. 둘 다 아니면 값을 넣어도 아무도 읽지 않는다 — \
						relaxed binding 은 표기를 흡수할 뿐 접두사의 위치를 옮겨 주지 않는다 (#419).

						고칠 자리는 대개 템플릿이다: 그 블록을 소유 접두사 아래로 옮긴다\
						(예: 루트 cors: → app: 아래 cors:). 접두사 쪽을 옮기는 결정이라면 코드·오류 \
						메시지·.env.example 문서가 함께 움직인다.""",
						orphans, new TreeSet<>(FRAMEWORK_ROOTS), boundRoots)
				.isEmpty();
	}

	/**
	 * §7.3 — 프레임워크 소유가 아닌 모든 <b>leaf 경로</b>는 어느 접두사의 자손이어야 한다.
	 *
	 * <p>최상위 키가 맞아도 그 아래에서 어긋날 수 있다 — {@code ai.providers.<이름>} 처럼 형제 키가
	 * 각자 다른 어댑터를 가리키는 자리에서는, 구현되지 않은 어댑터의 키가 "설정했다"는 착각만
	 * 남긴다.
	 */
	@Test
	void S7_3_every_leaf_binds_under_a_configuration_properties_prefix() {
		Set<String> prefixes = boundPrefixes();

		List<String> unbound = new ArrayList<>();
		for (Map<String, Object> document : documents(TEMPLATE)) {
			Map<String, Object> leaves = new LinkedHashMap<>();
			flatten("", document, leaves);
			for (String path : leaves.keySet()) {
				String key = canonical(path);
				if (FRAMEWORK_ROOTS.contains(key.split("\\.", 2)[0])) {
					continue;
				}
				boolean bound = prefixes.stream().anyMatch(p -> key.equals(p) || key.startsWith(p + "."));
				if (!bound) {
					unbound.add(path);
				}
			}
		}

		assertThat(unbound)
				.as("""
						application.yml.template 의 키 %s 아래에 놓인 값을 읽는 \
						@ConfigurationProperties 가 없다.

						바인딩되는 접두사는 %s 다. 키가 이 중 하나와 같거나 그 자손이어야 한다.

						둘 중 하나다. (1) 자리가 틀렸다 — 소유 접두사 아래로 옮긴다. (2) 그 기능이 아직 \
						없다 — 구현될 때까지 주석으로 둔다. 구현된 것은 실키, 구현되지 않은 것은 주석이다 \
						(#419).

						※ 이 검사는 @ConfigurationProperties 만 본다. @Value / @Scheduled 플레이스홀더로만 \
						읽는 키(예: 배치 주기)는 여기 잡히므로, 그런 키를 템플릿에서 살리려면 그 사실 \
						자체가 먼저 결정 대상이다.""",
						unbound, prefixes)
				.isEmpty();
	}

	/**
	 * §7.3 — 한 문서 안에 같은 최상위 키가 두 번 나오면 안 된다.
	 *
	 * <p><b>파싱 결과로는 알 수 없다.</b> SnakeYAML 은 중복 키에 예외를 던지지 않고 경고만 낸 뒤
	 * <b>마지막 것만 남긴다</b> — 앞의 블록은 통째로 사라진다. 템플릿에는 주석으로 준비된 블록이
	 * 있어서, 그것이 루트에 {@code # app:} 로 적혀 있으면 주석을 벗기는 순간 {@code app:} 이 두 번이
	 * 되고 <b>위쪽 app: 의 datasource 가 조용히 없어진다.</b> 그래서 준비된 블록은 소유 블록 안의
	 * 주석으로 두고, 이 검사가 되살아나는 것을 막는다 (#419).
	 *
	 * <p>원문을 줄 단위로 읽는다. 문서 경계({@code ---})마다 다시 센다.
	 */
	@Test
	void S7_3_no_root_key_appears_twice_in_one_document() {
		List<String> duplicates = new ArrayList<>();
		Set<String> seen = new TreeSet<>();
		int document = 1;
		int lineNumber = 0;

		for (String line : lines()) {
			lineNumber++;
			if (line.startsWith("---")) {
				document++;
				seen.clear();
				continue;
			}
			Matcher matcher = ROOT_KEY.matcher(line);
			if (matcher.matches() && !seen.add(matcher.group(1))) {
				duplicates.add("문서 %d, %d 번째 줄의 `%s:`".formatted(document, lineNumber, matcher.group(1)));
			}
		}

		assertThat(duplicates)
				.as("""
						application.yml.template 의 한 문서 안에서 같은 최상위 키가 두 번 선언됐다: %s

						SnakeYAML 은 이것을 예외로 만들지 않는다. 경고만 내고 마지막 선언만 남기므로 \
						앞의 블록은 통째로 사라지고, 그 사실은 부팅 실패가 아니라 "설정한 값이 왜 \
						안 먹지"로 나타난다.

						같은 접두사의 블록은 하나로 합친다. 주석으로 준비해 둘 블록이라면 루트에 \
						`# app:` 으로 적지 말고, 이미 있는 블록 안에 한 단계 더 들여쓴 주석으로 둔다 — \
						`# ` 만 떼면 자리가 맞아야 한다 (#419).""", duplicates)
				.isEmpty();
	}

	/**
	 * §7.3 — 템플릿은 {@code .env} 를 설정으로 들이는 줄을 가져야 한다 (#428).
	 *
	 * <p><b>자리가 맞는 것과 값이 도착하는 것은 다른 문제다.</b> {@code .env} 는 docker-compose 만
	 * 읽고 Boot 에는 dotenv 로더가 없다. 이 줄이 없으면 템플릿이 쓴 모든 {@code ${VAR}} 가 리터럴
	 * 문자열로 남고, 그것은 "값이 비었다"가 아니라 <b>"값이 들어 있다"</b>로 읽힌다 — 예컨대
	 * {@code auth.jwt.secret} 은 14바이트짜리 문자열이 되어 "32바이트 미만"으로 부팅을 세운다.
	 * 읽는 사람은 {@code .env} 의 그 항목을 확인하러 가고, 거기에는 충분히 긴 값이 들어 있다.
	 *
	 * <p><b>CI 는 이 검사만 할 수 있다.</b> "cp 로 만든 설정이 실제로 뜨는지"를 CI 가 직접 보려면
	 * {@code .env} 가 있어야 하는데, 값이 없으면 부팅이 서는 것이 설계다(§7.3). 그래서 여기서
	 * 보는 것은 <b>템플릿이 그 줄을 갖고 있는가</b> 하나이며, 그것은 {@code .env} 없이도 성립한다.
	 *
	 * <p>{@code optional:} 을 함께 요구한다. 파일이 없어도 실패하지 않아야 배포가 진짜 환경변수로
	 * 뜬다 — 필수 import 로 바뀌면 이 줄 자체가 배포를 막는다. 프로파일이 붙은 문서는 조건부이므로
	 * 세지 않는다: {@code dev} 에서만 로딩되는 {@code .env} 는 같은 실패를 그대로 남긴다.
	 */
	@Test
	void S7_3_the_template_imports_the_dotenv_file() {
		List<String> declared = new ArrayList<>();
		for (Map<String, Object> document : documents(TEMPLATE)) {
			Map<String, Object> leaves = new LinkedHashMap<>();
			flatten("", document, leaves);
			boolean conditional =
					leaves.keySet().stream().anyMatch(path -> path.startsWith("spring.config.activate"));
			Object imported = leaves.get("spring.config.import");
			if (conditional || imported == null) {
				continue;
			}
			if (imported instanceof List<?> many) {
				many.forEach(one -> declared.add(String.valueOf(one)));
			}
			else {
				declared.add(String.valueOf(imported));
			}
		}

		assertThat(declared)
				.as("""
						application.yml.template 에 .env 를 들이는 spring.config.import 가 없다 \
						(프로파일 없는 문서 기준). 지금 선언된 것: %s

						이 줄이 없으면 템플릿의 모든 ${VAR} 가 리터럴 문자열로 남는다. .env 는 \
						docker-compose 만 읽고 Boot 에는 dotenv 로더가 없기 때문이다. 그러면 README \
						대로 cp 한 설정이 "시크릿이 짧다" 류의 메시지로 부팅에 실패하고, 진짜 원인은 \
						메시지 어디에도 나오지 않는다 (#428).

						값은 optional: 로 시작하고 .env 를 가리켜야 한다. optional: 이 빠지면 파일이 \
						없는 환경(배포·CI)에서 이 줄 자체가 부팅을 막는다.""", declared)
				.anySatisfy(one -> assertThat(one).startsWith("optional:").contains(".env"));
	}

	/**
	 * §7.3 — 로컬 {@code application.yml} 은 템플릿이 알려 주지 않는 키를 가져서는 안 된다
	 * (#428).
	 *
	 * <p><b>#428 이 3개월을 살아남은 방식이 이것이다.</b> {@code application.yml} 은 {@code *.yml}
	 * 로 추적되지 않으므로(§7.2) 로컬 사본이 앞서 나가도 <b>diff 가 보이지 않는다.</b> 개발자의
	 * 사본에는 {@code spring.config.import} 가 있었고 왜 필요한지가 주석으로 적혀 있었지만, 그
	 * 지식이 템플릿으로 돌아오지 않았다. 그동안 새로 클론한 사람만 부팅에 실패했다.
	 *
	 * <p><b>한쪽 방향만 본다.</b> 로컬에만 있는 키는 <i>템플릿이 모르는 지식</i>이고, 그것을 아는
	 * 사람은 그 사본을 가진 한 명뿐이다. 반대로 템플릿에만 있는 키는 지식의 손실이 아니다 — 안내는
	 * 그대로 남아 있고, 그 값이 정말 필요하면 §7.3 대로 부팅이 서서 곧바로 드러난다.
	 *
	 * <p><b>키만 본다. 값은 읽지도 비교하지도 않는다</b> (S-11) — 로컬 사본에는 실제 시크릿이 들어
	 * 있고, 값을 보는 순간 그것이 테스트 로그에 실린다. 표기 차이는 {@code canonical} 이 지운다.
	 *
	 * <p>파일이 없으면 건너뛴다. CI 에는 {@code application.yml} 이 없으므로 이 검사는 <b>로컬
	 * 전용</b>이다 — CI 에서 성립하는 몫은 {@link #S7_3_the_template_imports_the_dotenv_file()} 이
	 * 맡는다.
	 */
	@Test
	void S7_3_the_local_copy_declares_no_key_the_template_does_not_teach() {
		assumeTrue(onClasspath(LOCAL_COPY),
				"application.yml 이 클래스패스에 없다 — 템플릿만 있는 환경(CI)에서는 대조할 사본이 없다");

		Set<String> ahead = new TreeSet<>(leafKeys(LOCAL_COPY));
		ahead.removeAll(leafKeys(TEMPLATE));

		assertThat(ahead)
				.as("""
						로컬 application.yml 에만 있고 application.yml.template 에는 없는 키: %s

						템플릿은 이 설정을 처음 만드는 사람에게 유일한 안내다. 사본에만 있는 키는 그 \
						안내에 없는 지식이고, application.yml 은 추적되지 않으므로 그 어긋남은 diff 로 \
						드러나지 않는다 — #428 의 spring.config.import 가 정확히 그렇게 3개월을 \
						살아남았다.

						둘 중 하나다. (1) 그 키가 모두에게 필요하다 — 템플릿에 되가져온다(값이 아니라 \
						자리와 이유를). (2) 이 기계에서만 쓰는 실험이다 — 사본에서 지운다.

						※ 키 이름만 본다. 값은 읽지 않는다 (S-11).""", ahead)
				.isEmpty();
	}

	/**
	 * 이 레포가 실제로 바인딩하는 접두사. <b>스캔한다</b> — 목록을 손으로 적으면 프로퍼티가 늘 때마다
	 * 낡고, 낡은 목록은 대조를 다시 사람의 눈으로 되돌린다 (#419).
	 */
	private static Set<String> boundPrefixes() {
		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));

		Set<String> prefixes = new TreeSet<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
			Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(),
					ApplicationTemplateBindingTests.class.getClassLoader());
			ConfigurationProperties annotation =
					AnnotatedElementUtils.findMergedAnnotation(type, ConfigurationProperties.class);
			String prefix = (annotation != null) ? annotation.prefix() : null;
			if (StringUtils.hasText(prefix)) {
				prefixes.add(canonical(prefix));
			}
		}

		assertThat(prefixes)
				.as("%s 에서 @ConfigurationProperties 를 하나도 찾지 못했다. 스캔이 비면 이 검사는 "
						+ "아무것도 지키지 못한 채 초록이 된다", BASE_PACKAGE)
				.isNotEmpty();
		return prefixes;
	}

	/**
	 * 한 파일(모든 문서)의 leaf 경로. <b>키만 꺼낸다</b> — 값은 호출자에게 넘기지 않는다 (S-11).
	 */
	private static Set<String> leafKeys(String resource) {
		Set<String> keys = new TreeSet<>();
		for (Map<String, Object> document : documents(resource)) {
			Map<String, Object> leaves = new LinkedHashMap<>();
			flatten("", document, leaves);
			leaves.keySet().forEach(path -> keys.add(canonical(path)));
		}
		return keys;
	}

	/** {@link #open(String)} 과 달리 <b>없어도 실패하지 않는다.</b> 로컬 전용 검사의 전제 확인용. */
	private static boolean onClasspath(String resource) {
		return ApplicationTemplateBindingTests.class.getResource(resource) != null;
	}

	/** YAML 은 {@code ---} 로 갈린 여러 문서다. 하나만 읽으면 뒤 문서가 검사 밖에 남는다. */
	private static List<Map<String, Object>> documents(String resource) {
		List<Map<String, Object>> documents = new ArrayList<>();
		try (InputStream stream = open(resource)) {
			for (Object loaded : new Yaml().loadAll(stream)) {
				if (loaded instanceof Map<?, ?> document) {
					Map<String, Object> typed = new LinkedHashMap<>();
					document.forEach((key, value) -> typed.put(String.valueOf(key), value));
					documents.add(typed);
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		assertThat(documents).as("%s 에서 읽은 YAML 문서가 없다", resource).isNotEmpty();
		return documents;
	}

	private static List<String> lines() {
		try (BufferedReader reader =
				new BufferedReader(new InputStreamReader(open(TEMPLATE), StandardCharsets.UTF_8))) {
			return reader.lines().toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * <b>클래스패스에서 읽는다.</b> 상대 경로로 읽으면 워크트리·CI 에서 작업 디렉터리가 달라져
	 * 파일을 못 찾고, 그때 실패하는 것은 검사 대상이 아니라 검사 자신이다.
	 */
	private static InputStream open(String resource) {
		InputStream stream = ApplicationTemplateBindingTests.class.getResourceAsStream(resource);
		assertThat(stream).as("%s 가 클래스패스에 없다", resource).isNotNull();
		return stream;
	}

	private static void flatten(String prefix, Map<?, ?> map, Map<String, Object> leaves) {
		map.forEach((key, value) -> {
			String path = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
			if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
				flatten(path, nested, leaves);
			}
			else {
				leaves.put(path, value);
			}
		});
	}

	/**
	 * relaxed binding 이 흡수하는 표기 차이를 지운다. 이 검사가 보는 것은 <b>표기가 아니라 위치</b>
	 * 이므로, {@code imageStorage} 와 {@code image-storage} 를 다른 키로 읽으면 없는 어긋남을
	 * 보고하게 된다.
	 */
	private static String canonical(String path) {
		StringBuilder canonical = new StringBuilder(path.length() + 4);
		for (char character : path.toCharArray()) {
			if (Character.isUpperCase(character)) {
				canonical.append('-').append(Character.toLowerCase(character));
			}
			else {
				canonical.append(character == '_' ? '-' : character);
			}
		}
		return canonical.toString();
	}
}
