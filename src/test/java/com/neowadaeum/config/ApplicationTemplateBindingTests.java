package com.neowadaeum.config;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>컨테이너를 쓰지 않는다 — 파일과 클래스패스만 본다. 빠른 루프(`./gradlew test`)에서 매 PR 마다
 * 돈다.
 */
class ApplicationTemplateBindingTests {

	private static final String TEMPLATE = "/application.yml.template";

	private static final String BASE_PACKAGE = "com.neowadaeum";

	/**
	 * 프레임워크가 소유한 최상위 네임스페이스. <b>이 넷만 하드코딩한다</b> — 우리가 정의하는 이름이
	 * 아니라 Spring 이 정의하는 이름이라, 우리 쪽 프로퍼티가 늘어도 이 집합은 늘지 않는다. 반대로
	 * 우리 네임스페이스를 여기 적기 시작하면 이 검사는 자기가 지키려던 것을 지키지 못한다.
	 */
	private static final Set<String> FRAMEWORK_ROOTS = Set.of("spring", "management", "logging", "springdoc");

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
		for (Map<String, Object> document : documents()) {
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
		for (Map<String, Object> document : documents()) {
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

	/** 템플릿은 {@code ---} 로 갈린 여러 문서다. 하나만 읽으면 뒤 문서가 검사 밖에 남는다. */
	private static List<Map<String, Object>> documents() {
		List<Map<String, Object>> documents = new ArrayList<>();
		try (InputStream stream = open()) {
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
		assertThat(documents).as("%s 에서 읽은 YAML 문서가 없다", TEMPLATE).isNotEmpty();
		return documents;
	}

	private static List<String> lines() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(), StandardCharsets.UTF_8))) {
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
	private static InputStream open() {
		InputStream stream = ApplicationTemplateBindingTests.class.getResourceAsStream(TEMPLATE);
		assertThat(stream).as("%s 가 클래스패스에 없다", TEMPLATE).isNotNull();
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
