# 운영 런북 (B-64)

> **장애 중에 판단을 새로 하지 않는다.** 무엇을 보고 무엇을 누르는지가 미리 적혀 있어야 한다.

관측은 B-48 이 만들었다. 지표가 있어도 그것을 보고 **무엇을 할지**가 없으면 장애 중에 코드를 읽게 된다.

| 런북 | 언제 |
|---|---|
| [Provider 장애](provider-outage.md) | 턴이 안 나온다 · fallback 이 튄다 · 타임아웃이 늘었다 |
| [세이프티 오탐 급증](safety-false-positives.md) | 멀쩡한 턴이 막힌다 · 차단율이 갑자기 올랐다 |
| [비용 폭주](cost-spike.md) | 토큰·호출이 평소의 몇 배다 |
| [유출 대응](data-exposure.md) | 키·프롬프트 원문·회원정보가 나갔다 |

## 공통 — 먼저 보는 것

```
GET /actuator/health      전체 상태
GET /actuator/prometheus  지표
```

| 지표 | 태그 | 무엇을 말하는가 |
|---|---|---|
| `play.turn.outcome` | `status` | 턴이 어떻게 끝났는가 |
| `play.turn.duration` | `status` | 턴 하나에 걸린 시간 |
| `ai.call` | `provider` `model` `purpose` `outcome` | Provider 호출의 성패 |
| `ai.call.latency` | `provider` `model` `purpose` | Provider 응답 시간 |
| `ai.call.fallback` | `from` `to` | **지목된 provider 가 죽었다** |
| `ai.call.tokens` | `provider` `model` `purpose` `direction` | 토큰 사용량 |
| `ai.call.cost.micro.krw` | `provider` `model` `purpose` | 비용 |
| `safety.judgement` | `level` `outcome` | 레벨별 통과·차단 |
| `safety.blocked.category` | `level` `category` | 무엇으로 막혔는가 |

**원문은 지표에 없다.** 프롬프트·응답 원문은 `ai_call_log`(promptlog 스토어)에만 있고, 그것을 읽는 경로는
감사 기록을 남긴다 (R12.3). 애플리케이션 로그에서 원문을 찾지 않는다 — **없는 것이 맞다** (S-3).

## 공통 — 하지 말아야 할 것

- **`.env` 를 열어 값을 로그·이슈·PR 에 붙이지 않는다.** 이 레포는 공개다 (S-11).
- **임계값과 판정 기준을 공개 채널에 적지 않는다.** 값을 알면 그 아래로 관리할 수 있다 (§13-12).
- **세이프티를 끄지 않는다.** L2 는 provider 와 무관하게 항상 서버에서 돈다 (I-13). 끄는 스위치를 만들지 않는다.
- **DB 를 직접 고치기 전에 배치와 마이그레이션을 확인한다.** 되돌릴 수 있는 것은 이미지뿐이다
  (`docs/deployment.md`).
