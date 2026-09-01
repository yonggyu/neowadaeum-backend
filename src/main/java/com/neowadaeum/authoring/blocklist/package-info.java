/**
 * <b>{@code authoring} 이 블록리스트에 대해 내주는 것</b> (B-49, ADR-0002).
 *
 * <p>{@code @NamedInterface} 로 <b>이 패키지만</b> 노출한다. 관리 화면(admin)이 등록·삭제를
 * 부르고, 세이프티는 {@code common/spi} 로 <b>읽기만</b> 한다 — 두 길이 다르다.
 *
 * <p><b>S-11 — 이 패키지의 코드·주석·테스트 이름에 실제 항목을 적지 않는다.</b>
 *
 * <p>위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@NamedInterface("blocklist")
package com.neowadaeum.authoring.blocklist;

import org.springframework.modulith.NamedInterface;
