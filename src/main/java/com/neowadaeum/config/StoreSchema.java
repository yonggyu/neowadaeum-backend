package com.neowadaeum.config;

/**
 * §5.3 4-스토어. 스키마 이름 · DataSource 빈 이름 · Flyway 마이그레이션 경로의 단일 출처다.
 *
 * <p>세 값을 파일마다 문자열로 되풀이하면 하나만 어긋나도 조용히 다른 스키마에 붙는다.
 * 여기 한 곳에서만 정의하고, 설정과 테스트가 모두 이 enum 을 참조한다.
 *
 * <p>빈 이름은 §5.3 표의 표기를 그대로 따른다({@code promptLogDataSource} 만 카멜 표기).
 * 프로퍼티 키({@code app.datasource.promptlog})와 철자가 다르다 — 의도된 것이며 문서가 그렇다.
 */
public enum StoreSchema {

	/** 회원·인증·동의·생년월일. 물리 분리 대상이다. */
	IDENTITY("identity", "identityDataSource"),

	/** 작품·버전·챕터·엔딩 + Authoring. */
	CATALOG("catalog", "catalogDataSource"),

	/** 세션·턴·스냅샷·요약. */
	PLAY("play", "playDataSource"),

	/** 요청/응답 원문·usage·감사 로그. 물리 분리 대상이다. */
	PROMPTLOG("promptlog", "promptLogDataSource");

	private final String schema;
	private final String dataSourceBeanName;

	StoreSchema(String schema, String dataSourceBeanName) {
		this.schema = schema;
		this.dataSourceBeanName = dataSourceBeanName;
	}

	/** PostgreSQL 스키마 이름. 접속 계정 이름은 {@code <schema>_user} 다. */
	public String schema() {
		return schema;
	}

	/** §5.3 표가 지정한 DataSource 빈 이름. */
	public String dataSourceBeanName() {
		return dataSourceBeanName;
	}

	/** §5.3 표가 지정한 Flyway 마이그레이션 경로. 스토어마다 완전히 분리된다. */
	public String migrationLocation() {
		return "classpath:db/migration/" + schema;
	}

	/** 이 스토어의 마이그레이션 실행 빈 이름. DataSource 빈 이름과 짝을 이룬다. */
	public String migrationBeanName() {
		return dataSourceBeanName().replace("DataSource", "Migration");
	}
}
