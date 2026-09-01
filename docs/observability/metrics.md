# 메트릭과 알람 (B-48, §12)

측정하지 않으면 **터진 뒤에 로그를 뒤지는 것**이 유일한 대응이 된다. 이 문서는 무엇을 세는지와, 그 숫자가 어떤 모양일 때 사람을 부르는지를 적는다.

## 원칙

**태그에 회원 식별정보도 세션 id 도 넣지 않는다** (I-3). 카디널리티가 곧 비용이라는 이유보다 먼저, 그것은 개인정보다. 태그 값은 **미리 정해진 짧은 목록**에서만 온다 — provider · model · purpose · outcome · level · category · direction · status.

**원문을 넣지 않는다** (S-3). 걸린 텍스트도 프롬프트도 메트릭의 관심사가 아니다. 원문 보관처는 `ai_call_log` 하나다.

**차단율은 나눗셈이 아니라 두 카운터다.** 비율만 기록하면 분모를 잃는다 — 차단율 10% 가 10건 중 1건인지 10만 건 중 1만 건인지는 다른 사건이다.

**계측 실패가 서비스를 막지 않는다.** 세는 일이 남기는 일보다, 남기는 일이 답하는 일보다 중요할 수 없다.

## 무엇을 세는가

| 메트릭 | 종류 | 태그 | 무엇을 답하는가 |
|---|---|---|---|
| `play.turn.duration` | timer | `status` | 턴이 얼마나 걸리는가. **차단된 턴은 대개 짧으므로** 상태별로 나눈다 — 함께 재면 p95 가 낮게 보인다 |
| `play.turn.outcome` | counter | `status` | 생성 · 엔딩 · 세이프티 차단. **차단도 하나의 결과다** — 실패로 세면 에러율과 섞인다 |
| `safety.judgement` | counter | `level` · `outcome` | L1(입력) · L2(출력)의 통과와 차단. 둘은 다른 사건이므로 섞지 않는다 |
| `safety.blocked.category` | counter | `level` · `category` | 어느 분류가 늘었는가. 이것이 없으면 **오탐 급증**에 대응할 수 없다 |
| `ai.call` | counter | `provider` · `model` · `purpose` · `outcome` | Provider 실패율. **응답 원문이 비어 있으면 실패**로 센다 — 그 정의를 여기서 못박지 않으면 대시보드마다 다르게 센다 |
| `ai.call.latency` | timer | `provider` · `purpose` | 느린 원인이 모델인지 우리인지. `play.turn.duration` 과의 차이가 서버 몫이다 |
| `ai.call.tokens` | counter | `provider` · `model` · `purpose` · `direction` | 입력과 출력은 단가가 다르다 |
| `ai.call.cost.micro` | counter | `provider` · `model` | 비용. 마이크로 단위 누적이며 대시보드가 기간으로 나눈다 |
| `ai.call.fallback` | counter | `from` · `to` | fallback 이 얼마나 도는가 (R3.7) — **그것이 곧 원래 provider 의 건강 상태**다 |

`/actuator/prometheus` 로 나간다.

## 알람 — 규칙은 여기 두지 않는다

**임계값은 실측 없이 정할 수 없다.** B-46 이 p95 실측치를 `docs/perf/` 에 남기면 그 값에서 시작한다. 지금 숫자를 적으면 그것은 근거가 아니라 추측이며, **추측한 임계값은 알람을 끄게 만든다.**

규칙 자체도 이 레포에 두지 않는다 — 배포 환경의 것이며 B-63 이 넣는다. 여기 적는 것은 **무엇을 보고 사람을 불러야 하는가**다.

| 무엇 | 왜 사람을 불러야 하는가 | 근거가 될 신호 |
|---|---|---|
| **Provider 실패율 급등** | 이야기가 만들어지지 않는다. 사용자에게는 서비스가 죽은 것과 같다 | `ai.call{outcome="failure"}` 의 비율 |
| **fallback 상시 발동** | 지목한 provider 가 사실상 죽었는데 **서비스는 멀쩡해 보인다** — 조용히 다른 모델로 이야기가 만들어지고 있다 | `ai.call.fallback` 이 0 이 아닌 상태의 지속 |
| **세이프티 차단율 급등** | 오탐이면 정상 플레이가 막히고, 정탐이면 그런 입력이 몰려오고 있다. **둘 다 즉시 봐야 한다** | `safety.judgement{outcome="blocked"}` 의 비율과 `safety.blocked.category` 의 분포 변화 |
| **턴 지연 p95 상승** | 25초 예산에 가까워지면 타임아웃이 늘고, 그 앞에 커넥션 풀이 먼저 마른다 | `play.turn.duration{status="generated"}` |
| **비용 기울기 변화** | 모델 단가나 프롬프트 길이가 바뀌었다는 뜻이다. **월말에 알면 늦다** | `ai.call.cost.micro` 의 시간당 증가량 |

각 시나리오의 대응 절차는 운영 런북(B-64)이다.

## 범위 밖

- **에러율**은 별도로 세지 않는다. Actuator 의 `http.server.requests` 가 상태 코드별로 이미 센다 — 같은 것을 두 번 세면 두 숫자가 어긋나는 날이 온다
- **대시보드 정의**(Grafana JSON 등)는 배포 환경의 것이다 (B-63)
