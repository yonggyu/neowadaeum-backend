plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.neowadaeum"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springModulithVersion"] = "2.1.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	// B-06 — 계약 우선. springdoc 은 **수기 계약 파일을 보여 주는 UI** 로만 쓴다.
	// 런타임 자동 생성(springdoc.api-docs)은 꺼 둔다 — 생성본이 두 번째 진실이 되면
	// docs/openapi.yaml 이 진실의 원천이라는 규칙이 무너진다 (CLAUDE.md Source of Truth).
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
	implementation("org.springframework.modulith:spring-modulith-starter-insight")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.springframework.modulith:spring-modulith-runtime")
	annotationProcessor("org.projectlombok:lombok")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// 계약 테스트용 고정 응답 서버 (.claude/rules/testing.md — "계약 | Provider 어댑터 | WireMock").
	// standalone 은 의존성을 셰이딩해 Boot 관리 버전과 충돌하지 않는다.
	testImplementation("org.wiremock:wiremock-standalone:3.13.1")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
	}
}

// ── 계약 파일을 산출물에 싣는다 (B-06) ───────────────────────
// docs/openapi.yaml 은 레포의 문서이면서 동시에 런타임이 서빙하는 계약이다. 두 벌로 두면
// 반드시 갈라지므로 **원본 하나를 빌드가 복사한다.**
//
// 도착지는 `openapi/` 다 — `static/` 도 `public/` 도 아니다. 그 둘은 Spring Boot 가 프로파일과
// 무관하게 서빙하므로, 계약 문서를 거기 두면 dev 전용 게이트를 통째로 우회한다
// (dev 콘솔 HTML 을 devconsole/ 에 둔 것과 같은 이유다, B-47).
tasks.processResources {
	from(layout.projectDirectory.file("docs/openapi.yaml")) {
		into("openapi")
	}
}

// ── 테스트 분리 (§10) ────────────────────────────────────────
// 컨테이너가 필요한 테스트는 기동 비용이 크고 Docker 데몬을 요구한다. 개발 루프에서 매번 돌리면
// 한 번의 확인이 수십 초가 되어 결국 테스트를 덜 돌리게 된다. 그래서 기본 `test` 에서 분리한다.
//
//   ./gradlew test              빠른 루프 — 컨테이너 없이 도는 것만
//   ./gradlew integrationTest   컨테이너 테스트 (Docker 필요)
//   ./gradlew test integrationTest   전부. CI 가 이렇게 돈다
//
// 분리는 "검증을 줄이는 것"이 아니다. CI 는 여전히 전부 돌린다(§8.9 — CI 가 승인 리뷰를 대체한다).
// 바뀌는 것은 로컬에서 저장할 때마다 무엇이 도는가뿐이다.
// 태그 2종은 서로 직교한다. `container` 는 "Docker 가 필요한가", `nightly` 는 "PR 마다 돌 만한가"다.
// 한 테스트가 둘 다일 수 있다 — 그때는 nightly 가 이긴다(어느 태스크에서든 PR 경로에서 빠진다).
//
//   ./gradlew test              빠른 루프 — 컨테이너도 nightly 도 아닌 것
//   ./gradlew integrationTest   컨테이너 테스트 (Docker 필요). nightly 는 제외
//   ./gradlew nightlyTest       nightly 로 분류된 것 (ADR-0001)
//
// ci.yml 은 test + integrationTest 를, nightly.yml 은 nightlyTest 를 돌린다.
// 어느 것도 "안 돌린다"가 아니다 — 도는 시점만 다르다.
//
// useJUnitPlatform() 을 tasks.withType<Test> 에서 한 번, 태스크별로 또 한 번 호출하지 않는다.
// 두 번 호출하면 어느 쪽이 필터를 덮는지가 Gradle 버전에 달린 문제가 된다. 태스크마다 한 번만 쓴다.
tasks.test {
	useJUnitPlatform {
		excludeTags("container", "nightly")
	}
}

val integrationTest by tasks.registering(Test::class) {
	group = "verification"
	description = "Testcontainers 가 필요한 테스트. Docker 데몬이 있어야 한다."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("container")
		excludeTags("nightly")
	}
	shouldRunAfter(tasks.test)
}

val nightlyTest by tasks.registering(Test::class) {
	group = "verification"
	description = "PR 마다 돌리지 않는 테스트. 분류 근거는 docs/adr/0001-mvp-test-execution-policy.md."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("nightly")
	}
	// 예외를 두지 않는다 (#89). 여기에는 ADR-0001 시점의 임시 조치로 failOnNoDiscoveredTests = false 가
	// 걸려 있었다. 근거는 "nightly 대상이 0건이라 첫날부터 빨개진다" 였는데 **그 전제가 사라졌다** —
	// S-9-3(#67)에서 TurnConcurrencyNightlyTests 가 붙었고 이 태스크가 실제로 집어 간다.
	//
	// 전제가 살아 있던 동안에도 그 선언은 아무 일도 하지 않았다. Gradle 의 failOnNoDiscoveredTests 는
	// 태그 필터가 0개를 만든 실행을 잡지 못하므로(아래 가드 주석) 켜든 끄든 결과가 같았다.
	//
	// nightly 는 사람이 매일 보지 않는다. 조용한 0건이 가장 오래 숨는 자리가 여기다.
	shouldRunAfter(tasks.test, integrationTest)
}


// ── 빈 실행을 실패로 만든다 (#89) ──────────────────────────────
//
// Test 태스크는 **테스트를 하나도 돌리지 않아도 성공한다.** 태그 필터가 아무것도 고르지 못하면
// 조용히 초록이 되고, 그 초록은 "전부 통과했다"와 로그에서 구분되지 않는다.
//
// Gradle 의 failOnNoDiscoveredTests 는 이 경우를 잡지 못한다 — 실측으로 확인했다. 값을 true 로
// 명시해도 태그 필터가 0개를 만든 실행은 그대로 BUILD SUCCESSFUL 이다. 그래서 직접 센다.
//
// 세는 값의 출처는 JUnit XML 리포트다. 리스너로 세면 configuration cache 와 충돌하고, 리포트는
// 어차피 태스크가 남기는 산출물이라 새로 만드는 것이 없다.
//
// **세 태스크 모두에 같은 규칙이 걸린다** — test · integrationTest · nightlyTest. 지금 예외는 없다.
//
// 그래도 Gradle 의 failOnNoDiscoveredTests 를 스위치로 읽는다. 표준 플래그를 false 로 둔 태스크에
// 이 가드만 살아 있으면 빌드가 자기 모순이 된다 — 0건이 정상인 태스크가 생기면 그 사실을 **선언으로**
// 남기고, 그 선언이 여기에도 그대로 적용된다. 지금 그 선언을 한 태스크는 없다.
//
// UP-TO-DATE 로 건너뛴 태스크에는 doLast 가 돌지 않으므로 개수가 남지 않는다. 이전 실행이 이미
// 검증한 상태이므로 안전 문제는 아니다. **그 때문에 UP-TO-DATE 를 끄지는 않는다** — 캐시를 버리는
// 대가가 이 가드가 얻는 것보다 크고, CI 는 매번 새 체크아웃이라 해당되지 않는다.
tasks.withType<Test>().configureEach {
	// 실패의 **메시지**를 CI 로그에 남긴다.
	//
	// 기본 형식은 예외 종류와 줄 번호까지만 찍는다 — `java.lang.AssertionError at Foo.java:179`.
	// 컨테이너 테스트는 Docker 가 있는 곳에서만 돌므로 로컬에서 재현할 수 없고, 그 한 줄로는
	// 무엇이 어긋났는지 좁힐 수 없다. XML 리포트에는 메시지가 있지만 그것은 로그에 실리지 않는다.
	testLogging {
		events("failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}

	val guardEnabled = failOnNoDiscoveredTests
	val xmlDir = reports.junitXml.outputLocation
	val taskPath = path

	doLast {
		val executed = xmlDir.get().asFile.listFiles()
			.orEmpty()
			.filter { it.name.startsWith("TEST-") && it.extension == "xml" }
			.sumOf { file ->
				Regex("""<testsuite[^>]*\stests="(\d+)"""")
					.find(file.readText())
					?.groupValues?.get(1)?.toLong() ?: 0L
			}

		logger.lifecycle("$taskPath — 실행된 테스트 $executed 개")

		if (executed == 0L && guardEnabled.get()) {
			throw GradleException(
				"$taskPath 가 테스트를 하나도 돌리지 않았다. 태그 필터가 아무것도 고르지 못했거나 " +
					"테스트 클래스가 클래스패스에 없다. 0건이 정상인 태스크라면 " +
					"failOnNoDiscoveredTests = false 로 그 사실을 선언한다 (#89).")
		}
	}
}
