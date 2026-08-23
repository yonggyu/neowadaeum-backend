---
paths:
  - "src/main/java/com/neowadaeum/identity/**/*.java"
  - "src/test/java/com/neowadaeum/identity/**/*.java"
---

# identity — 회원 · 인증 · 동의

## playerRef 경계

- `player_ref`는 회원당 1개의 UUID이며 **회원정보와 무관**하다.
- **비-Identity 스토어(`catalog` · `play` · `promptlog`)는 `user.id`를 절대 저장하지 않는다.** `playerRef`만 전달한다.
- **`player_ref`를 API 응답에 노출하지 않는다** (S-9). UGC 작성자는 `authorDisplayName`만 노출한다.
- 닉네임 동기화는 Catalog 파사드로 한다 — `author_profile`이 Catalog 소유다(스토어 분리 원칙상 Catalog가 Identity를 조회하지 않는다).

## 연령 게이트

- 만 나이는 **요청 시각(KST) 기준**으로 계산한다. `birth_date` 원본을 저장하고 **나이를 캐시하지 않는다**(생일 경과).
- 만 15세 미만 → `403 AGE_RESTRICTED`, **계정을 만들지 않는다.**
- `birthDate` 또는 `consents[]` 누락 → `400 CONSENT_REQUIRED`.
- 경계값(만 15세 되기 하루 전 / 당일) 테스트가 필수다.

## 동의와 고지

- `consent_log`는 **동의** 기록이다. `consent_type` · `agreed_at` · `version` · `ip_hash`.
- **AI 사전 고지 노출 이력은 `ai_notice_impression`에 따로 남긴다.** `consent_log`에 섞으면 동의 이력의 법적 증빙력이 흐려진다.
- 고지 문구를 코드에 하드코딩하지 않는다 — `service_config`에서 읽는다.

## 인증

- MVP는 **Google OAuth만** 구현한다. 이메일 가입은 범위 밖이다.
- Google 로그인은 OAuth2 Client, 자체 발급 JWT 검증은 Resource Server의 Nimbus 디코더를 재사용한다.
- **`dev` 프로파일의 고정 `playerRef`는 편의가 아니라 인증 우회다.** 도입한다면 **`prod`에서 만들어지지 않는다는 테스트가 같은 PR에 있어야 한다** (이슈 #34, ADR-0004). 프로파일 애노테이션이 붙어 있다는 확인으로 갈음하지 않는다.
