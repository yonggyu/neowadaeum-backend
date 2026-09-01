/**
 * 배치 실행 오케스트레이션. 스케줄링과 실행만 담당한다.
 *
 * <p><b>집계·만료·재스캔·파기의 실제 로직은 이 모듈이 갖지 않는다.</b> 데이터를 소유한 모듈이 구현하고,
 * batch 는 그 구현체를 주입받아 실행 시점만 관리한다. 규칙 자체는 §5.3(각 스토어는 자기 데이터만 만진다)의
 * 연장이며, 실행 인터페이스(SPI)는 {@code common/spi} 에 둔다. 근거는 ADR-0003 에 기록했다.
 *
 * <p>이 때문에 허용 의존이 {@code common} 하나로 고정된다. 대상 데이터의 소유 모듈은 다음과 같고,
 * batch 가 이들을 직접 참조하지 않는 것이 이 설계의 요점이다.
 *
 * <ul>
 *   <li>B-39 {@code ending_stat} 집계 — catalog(통계 테이블) / play(완주 세션)</li>
 *   <li>B-59 사후 검수 배치 — authoring(검수 큐·블록리스트) / safety(재스캔 판정)</li>
 *   <li>B-61 데이터 파기 — ai(프롬프트 로그) / play(세션 만료) / identity(탈퇴 삭제·익명화, player_ref 매핑)</li>
 *   <li>S-10 감사 로그 파기 — admin(감사 로그)</li>
 * </ul>
 *
 * <p>이 목록을 batch 의 허용 의존으로 옮기면 §5.4 의 경계가 사실상 사라지고,
 * {@code admin → batch} 와 {@code batch → admin} 이 만나 순환이 된다. SPI 방식은 그 둘을 함께 막는다.
 *
 * <p>ADR-0003 에서 함께 확정한 것 — SPI 는 <b>배치별 개별 인터페이스</b>다(공통 {@code BatchTask} 에
 * 식별자를 붙이지 않는다). ShedLock 잠금 이름은 <b>batch 소유</b>다("언제 어떻게 실행하는가"의 일부다).
 * 실행 결과 적재는 <b>구현 모듈</b>이 한다 — batch 가 적재하면 batch → authoring 의존이 생겨 이 경계가
 * 무너진다. batch 는 성공/실패와 소요를 구조화 로그로만 남긴다 (§9.4).
 *
 * <p>SPI 인터페이스와 각 모듈의 구현은 B-39 / B-59 / B-61 에서 추가한다. B-02 는 경계만 고정한다.
 */
@ApplicationModule(allowedDependencies = "common")
package com.neowadaeum.batch;

import org.springframework.modulith.ApplicationModule;
