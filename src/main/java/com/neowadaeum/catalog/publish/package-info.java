/**
 * <b>{@code catalog} 에 작품을 쓰는 유일한 길</b> (B-53, B-56).
 *
 * <p>{@code @NamedInterface} 로 <b>이 패키지만</b> 노출한다. 지금까지 catalog 는 읽기만 했고
 * ({@code catalog :: query}) 쓰는 경로가 없었다 — 미리보기(B-53)와 게시(B-56)가 그 길을 요구한다.
 *
 * <p><b>쓰는 길이 하나여야 하는 이유가 있다.</b> 작품 하나는 {@code story} · {@code story_version} ·
 * {@code chapter_def} · {@code ending_def} 가 <b>함께</b> 있어야 성립한다 — 여러 곳에서 쓰기
 * 시작하면 그중 하나만 있는 작품이 생긴다.
 *
 * <p>위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 */
@NamedInterface("publish")
package com.neowadaeum.catalog.publish;

import org.springframework.modulith.NamedInterface;
