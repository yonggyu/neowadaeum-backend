/**
 * 작품 정의 조회 (S-9-1).
 *
 * <p><b>이 패키지는 catalog 모듈의 API 다</b> — {@code @NamedInterface} 로 명시적으로 노출한다
 * (ADR-0005). 다른 모듈은 <b>이 패키지의 파사드로만</b> catalog 데이터를 얻는다. Repository·Entity
 * 직접 참조는 반려 대상이며(§5.4), 애초에 노출되지 않는다.
 *
 * <p>JPA 를 쓰지 않는 이유는 catalog EntityManagerFactory 가 아직 없기 때문이다(#20, `blocked`).
 * {@code catalogDataSource} 를 {@code JdbcClient} 로 읽으면 EMF 없이도 조회가 되고,
 * catalog 모듈이 catalog 스키마를 읽는 것이므로 §5.3 의 "각 계정은 자기 스키마에만"도 지켜진다.
 * 엔티티는 B-08 복귀 시점이다.
 */
@NamedInterface("query")
package com.neowadaeum.catalog.query;

import org.springframework.modulith.NamedInterface;
