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
	// ADR-0001 시점에 nightly 대상은 0건이다. 해당 테스트(§10.1 의 7 · 8 · 12)는 B-32 이후에 생긴다.
	// 0건에서 실패하면 nightly 워크플로가 첫날부터 빨개지고 실패 이슈가 자동으로 열린다.
	// 그러면 아무도 안 보게 되고, 정작 진짜 실패했을 때 구분되지 않는다.
	failOnNoDiscoveredTests = false
	shouldRunAfter(tasks.test, integrationTest)
}
