/**
 * 작품 만들기 화면이 고르게 할 것들 (§13-56, 이슈 #282 · #315).
 *
 * <p><b>내부 패키지다.</b> {@code @NamedInterface} 를 두지 않는다 — 이 목록을 읽는 것은
 * authoring 의 작성자 경로뿐이다.
 *
 * <p><b>정본이 둘로 갈린다.</b> 장르는 {@code catalog} 의 {@code genre} 표에서 오고(운영이
 * 늘릴 수 있다), 조건 템플릿은 코드의 열거형에서 온다(평가기가 지원하는 형태다). 어느 쪽도
 * 프론트가 상수로 들지 않게 하는 것이 이 패키지의 존재 이유다.
 */
package com.neowadaeum.authoring.metadata;
