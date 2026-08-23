/**
 * 작품 정의 조회 (S-9-1).
 *
 * <p><b>엔티티가 아니라 조회 파사드다.</b> 다른 모듈은 이 패키지의 파사드로만 catalog 데이터를
 * 얻는다 — Repository·Entity 직접 참조는 반려 대상이다 (§5.4).
 *
 * <p>JPA 를 쓰지 않는 이유는 catalog EntityManagerFactory 가 아직 없기 때문이다(#20, `blocked`).
 * {@code catalogDataSource} 를 {@code JdbcClient} 로 읽으면 EMF 없이도 조회가 되고,
 * catalog 모듈이 catalog 스키마를 읽는 것이므로 §5.3 의 "각 계정은 자기 스키마에만"도 지켜진다.
 * 엔티티는 B-08 복귀 시점이다.
 */
package com.neowadaeum.catalog.query;
