/**
 * 디버그·롤백·재생성·검수 큐·감사 로그.
 *
 * <p>§5.4 의 "admin ← 전 모듈"을 옮긴 것이되 {@code batch} 는 뺐다. 현재 요구사항에서 admin 이 batch 를
 * 직접 호출할 근거가 확인되지 않았고, batch 는 스케줄 실행만 담당하기 때문이다. 실제 요구가 생기면
 * 그때 의존 방향을 다시 검토한다.
 *
 * <p>§5.2 패키지 구조 / §5.4 모듈 간 의존 규칙. 허용 의존은 이 애노테이션이 유일한 진실의 원천이며,
 * 위반은 {@code ApplicationModules.verify()}가 빌드에서 잡는다.
 *
 * <p><b>{@code catalog :: query} 를 더했다</b> (#339). 세션 목록이 작품 이름을 함께 보여야
 * 하는데 그 값은 catalog 의 것이고, admin 은 어느 스토어에도 직접 닿지 않는다 — 파사드로만
 * 묻는다 (§5.4).
 */
@ApplicationModule(allowedDependencies = { "common", "identity :: access", "play :: debug",
		"ai :: debug", "authoring :: blocklist", "authoring :: report", "authoring :: review",
		"catalog :: query" })
package com.neowadaeum.admin;

import org.springframework.modulith.ApplicationModule;
