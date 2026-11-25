# WattUp 백엔드 개발 현황

## 작업 기준

- 기존 Docs 원본, API 명세 v3.0, DB 설계 및 ETA v4 ZIP과 멘토링 3회차 기록을 기준으로 사용한다. 화면 API 통합과 Android 정렬은 변경 제안으로 유지하고 기존 서버 Top-3 계약은 유지한다.
- 초기 개발 환경·MySQL Flyway V1·CI는 PR [#1](https://github.com/YU-AI-Service-Project/Backend/pull/1)의 병합 커밋 `6ff8050`을 기준으로 재사용한다.
- 완료 범주 A · 공단 HTTP 클라이언트, 메타데이터·상태 적재, 실행 이력, 중복 실행 방지, 재시도·일 한도와 스케줄링. PR [#3](https://github.com/YU-AI-Service-Project/Backend/pull/3)을 `550fd70`으로 Squash Merge했다.
- 완료 범주 B · 비회원 주변 검색, 상세, 충전기 목록, 현재 상태. PR [#4](https://github.com/YU-AI-Service-Project/Backend/pull/4)를 `7b3cb76`으로 Squash Merge했다.
- 완료 범주 C · 비회원 단건·배치 도착 예측과 Spring Boot 클라이언트. PR [#5](https://github.com/YU-AI-Service-Project/Backend/pull/5)를 `388e5bd`로 Squash Merge했다. 실제 FastAPI·카카오 연동은 별도 검증 대상이다.
- 완료 범주 D · 서버 Top-3 추천과 Android용 통합 응답. PR [#6](https://github.com/YU-AI-Service-Project/Backend/pull/6)을 `a3fcb9c`로 Squash Merge했다.
- 완료 범주 E · 소셜 로그인, 약관, 토큰·세션, 이메일 가입·비밀번호 API 14개. PR [#7](https://github.com/YU-AI-Service-Project/Backend/pull/7)을 `a245d73`으로 Squash Merge했다.
- 완료 범주 F · 내 계정·탈퇴 API 11개. PR [#8](https://github.com/YU-AI-Service-Project/Backend/pull/8)을 `22b72f5`로 Squash Merge했다.
- 완료 범주 G · 즐겨찾기·검색 설정·장소 주소 조회 API 8개. PR [#9](https://github.com/YU-AI-Service-Project/Backend/pull/9)을 `2d8d212`로 Squash Merge했다.
- 현재 범주 H · `feat/remaining-apis`. 분석·공지·신고·내부 운영 API와 OpenAPI를 제공한다. [GitHub PR 검색](https://github.com/YU-AI-Service-Project/Backend/pulls?q=is%3Apr+head%3Afeat%2Fremaining-apis)
- 범주 A의 수집 내부 서비스는 H의 관리자 HTTP 49–51에서 재사용한다. 공단 실연동은 인증키와 실호출 승인 후 검증한다.
- FastAPI 추론 경로 47·48의 소유자는 AI 서버다. Spring Boot는 계약 및 호출 클라이언트를 담당한다. 52번 AI 연결 상태는 Spring Boot 소유다.
- 각 범주의 CI와 병합 상태는 해당 PR을 기준으로 기록한다.

## 범주 A 검증과 인계

- `7ef1ba4` HTTP 계약·API 추적표, `535363d` 상태 이력과 적재, 후속 커밋 지역 인벤토리 정합성과 스케줄링.
- Flyway V2는 일 한도와 지연 관측 구분, V3는 지역별 인벤토리 수집 실행을 추가한다. V1은 기존 파일을 사용한다.
- Java 25·MySQL 8.4에서 `mvnw.cmd clean verify` 성공. 로컬 15개 통과, 실패·건너뜀 0개. 공단 인증키와 실호출 승인 확인 전까지 실제 API 및 대량 적재 처리 시간은 미검증 상태다.
- 실행 중 수집기 영구 중단이 있으면 다음 정상 실행이 미완료 실행을 종료한다. 상태 집계와 향후 AI 특징 생성은 완전한 수집 실행과 최신 상태에 반영된 관측을 기준으로 한다.
- [PR CI](https://github.com/YU-AI-Service-Project/Backend/actions/runs/45346380164)에서 새 MySQL에 V1–V3 적용과 테스트 15개 통과를 확인했다.

## 범주 B 검증과 인계

- 기존 스키마를 JDBC로 조회한다. 새 Flyway 마이그레이션은 없다.
- 목록 요청은 반경과 충전기 조건에 맞는 전체 충전소 수와 거리순 결과를 반환한다. 상세는 충전기와 집계 상태를 함께 반환한다.
- 10분 이상 된 관측에는 지연을 표시하고, 관측 시각이 없는 충전기는 상태 미확인으로 집계한다. `availableOnly`는 10분 이내 가용 관측이 있는 충전소만 선택한다.
- Java 25·MySQL 8.4에서 `mvnw.cmd clean verify`로 전체 테스트 21개가 통과했다. HTTP 테스트는 4개 경로, 필터, 오류 응답, 최신성, 비공개 충전기 제외를 검증한다. Android 실기기 테스트는 미실행이다.
- [PR CI](https://github.com/YU-AI-Service-Project/Backend/actions/runs/45348326127)에서 전체 테스트 21개 통과를 확인했다.

## 범주 C 검증과 인계

- 카카오 자동차 길찾기에서 도로 거리·ETA·요청 및 응답 수신 시각을 수집한다. MySQL의 완전한 지역별 수집에서만 5분 간격 관측 7개와 품질 지표를 재현한다.
- `asOf` 뒤에 지역의 전체 인벤토리가 갱신됐으면 다음 격자까지 예측을 유보한다. 첫 관측 전의 과거 샘플에는 해당 충전기를 집계하지 않는다. 현재 격자에서 호환 충전기가 미관측 상태이면 확률을 반환하지 않는다.
- ZIP의 `API_ETA_연동규격.md`와 `ai-eta-v4.openapi.json`은 `/internal/v2/arrival-predictions/batch`를 초안으로 제시한다. API 48번의 자체 경로는 유지하며, 배포 URL과 인증은 AI 팀과 합의한 값으로 설정한다. ZIP에는 실행 가능한 추론 함수와 모델 파일이 있다. 운영 FastAPI HTTP 서버는 아직 검증 대상이다.
- 동일한 합성 충전기 이력으로 ZIP의 `observation_builder.py`와 Java 계산의 입력 필드 29개를 대조했다. `predict_eta.py` 오프라인 재생에서 15분 격자, 확률 `0.975582926408748`, 지연 및 이력 부족 시 확률 `null`을 확인했다. 이는 재현용 결과다.
- 로컬 Java 25·MySQL 8.4에서 `mvnw.cmd clean verify` 전체 34개 통과. 단건과 최대 30건, 일부 장애, 잘못 매칭된 예측 시각을 검증했다. 운영 데이터 규모, 카카오 실호출 및 실제 FastAPI 배포 연동은 미검증이다. 모델 카드는 `production_ready=false`다.
- [PR CI](https://github.com/YU-AI-Service-Project/Backend/actions/runs/45363662885)에서 전체 테스트 34개가 통과했다.

## 범주 D 검증과 인계

- 명세 7번의 서버 Top-3 계약을 유지한다. 서버에서 후보를 선별하면 현재 상태·경로·예측을 한 응답에서 조합하고 동점 처리도 일관되게 적용할 수 있다. Android에서 순위를 계산하려면 전체 후보와 평가 조건을 받아야 하고 화면마다 결과가 달라질 수 있다. Android가 별도 정렬을 제공할 경우 후보 배열 범위와 표시 정책은 팀에서 합의한다.
- 멘토링에서는 낮을수록 좋은 `직선거리(km) × 2 + (1 − 가용 확률) × 15`를 비교안으로 제시했다. 이 식은 확률 0.20 차이와 거리 1.5km 차이를 같은 비용으로 보고, 현재 상태·출력·예측 미확인 데이터를 다루지 않는다.
- 구현한 검토용 점수는 0–1로 정규화했다. 도착 확률 50%, 최근 가용 대수 비율 25%, 반경 대비 직선거리 20%, 적합 충전기 최대 출력 5%를 더한다. 거리 점수는 `1 − 직선거리 / 반경`, 출력 점수는 `min(출력 / 350kW, 1)`이다. 동점은 직선거리와 충전소 ID 순으로 처리한다. 카카오 도로 거리와 ETA는 응답에 제공하며 후보 간 거리 지표는 같은 기준으로 비교한다.
- 확률을 확인할 수 없는 후보가 있으면 모든 후보에 현재 상태·직선거리·출력의 같은 기준을 적용한다. 확인한 확률은 화면에 제공하고, 확인하지 못한 확률은 `null`을 유지한다. 최소 출력 때문에 모델의 전체 프로필과 후보 충전기 집합이 달라지거나 조회 사이에 인벤토리 버전이 바뀌는 경우에도 같은 폴백을 사용한다. 이 가중치와 폴백 정책은 팀 승인 전 검토안이다.
- 로컬 Java 25·MySQL 8.4에서 전체 테스트 46개가 통과했다. HTTP/MySQL과 루프백 테스트에서 빈 결과, 최대 3곳, 필터·동점·최신성, 1·10·30건의 배치 및 일부 장애, 경로 시간 제한을 확인했다. 해당 실행의 루프백 테스트 케이스 소요 시간은 각각 16·10·14ms였다. 실제 카카오·FastAPI 운영 연동과 Android 실기기는 별도 검증 대상이다.
- [PR CI](https://github.com/YU-AI-Service-Project/Backend/actions/runs/45366254821)에서 전체 테스트 46개가 통과했다.

## 범주 E 검증과 인계

- Flyway V4에서 회원·약관·소셜·이메일·토큰·요청 제한 테이블을 만들고 V5에서 공급자 사용자 ID를 대소문자까지 구분한다. V1–V3를 재사용한다.
- 업체 확인은 카카오 앱 ID·토큰 소유자 대조와 Google 서명·발급자·클라이언트·만료 검증을 거친다. 계정 연결은 공급자와 사용자 ID로 결정한다. [카카오 로그인 REST](https://developers.kakao.com/docs/ko/kakaologin/rest-api) · [Google ID 토큰 검증](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)
- 액세스 JWT 인증 때 세션과 사용자 상태를 확인한다. 갱신, 재사용 탐지, 로그아웃, 비밀번호 갱신은 사용자 행 잠금으로 직렬화한다. OAuth 토큰은 저장하지 않는다. 갱신 토큰과 티켓은 해시, 이메일 인증 코드는 HMAC, 비밀번호는 BCrypt로 저장한다.
- 이메일 가입 코드 실패와 재발송 제한, 갱신 토큰 동시 제출, 토큰·티켓 재사용, 만료, 비회원 조회를 테스트한다. 이메일 재설정은 새 요청이 기존 토큰을 폐기하지 않으며, 비밀번호 갱신 시 남은 토큰을 한 번에 폐기한다.
- Java 25·MySQL 8.4에서 `mvnw.cmd clean verify` 전체 59개 통과, 실패·건너뜀 0개.
- 실제 OAuth 앱, SMTP 전달, 팀 약관 문서 배포와 Android 연동은 별도 확인이 필요하다. [PR CI](https://github.com/YU-AI-Service-Project/Backend/actions/runs/45420341708)에서 새 MySQL의 V1–V5 적용과 테스트 59개 통과를 확인했다.

## 범주 F 검증과 인계

- Flyway V6에서 탈퇴 시 삭제할 즐겨찾기·개인 설정 테이블을 만들었다. 해당 API는 G에서 이 테이블을 재사용한다. V7은 탈퇴용 티켓 조회 인덱스를 추가한다. V8은 사용자 식별자나 토큰 없이 소셜 해제 시각을 기록해 해제 이전에 시작된 검증 결과를 차단한다.
- 재인증은 비밀번호 또는 현재 연결된 소셜 신원을 검증한다. 5분짜리 티켓은 계정·기기·목적에 묶이며 성공하면 재사용할 수 없다. 본인 세션만 조회하고 종료할 수 있다.
- 탈퇴는 시작 설정에서 비활성화한다. 적용 시 상태 기록을 남기고 기존 세션·자격 증명·연결·티켓·동의 이력·개인 설정을 삭제한다. 운영 정책은 배포 전에 확정한다.
- 카카오 연결 해제는 관리자 키로 요청한다. 테스트 대역에서 외부 실패 시 로컬 상태 유지, 외부 성공 뒤 로컬 롤백과 재시도, 동시 로그인·연결 요청 차단을 확인한다. 재시도의 기존 티켓이 만료되면 다시 재인증해야 한다. Google OAuth 권한 철회는 앱에서 사용할 권한 및 토큰 종류를 팀이 확정해야 한다.
- Java 25·MySQL 8.4에서 `mvnw.cmd clean verify` 전체 68개 통과, 실패·건너뜀 0개. 실제 카카오 앱의 연결 해제와 Android 실기기 동작은 별도 확인이 필요하다.
- [PR CI](https://github.com/YU-AI-Service-Project/Backend/actions/runs/45437024228)에서 V1–V8 적용과 테스트 68개 통과를 확인했다.

## 범주 G 검증과 인계

- 명세 39–44번은 JWT에서 확인한 계정에만 적용한다. V6의 즐겨찾기·개인 설정 테이블을 재사용한다. 즐겨찾기 추가·삭제·병합 시 현재 세션과 사용자 행을 잠가 중복 삽입과 탈퇴 후 쓰기를 방지한다.
- 화면 명세서의 수동 위치 검색에서 필요한 도로명·지번·장소명을 제공한다. 지도 표시·터치·GPS 기준점 이동은 Android의 지도 기능을 사용한다. 주소를 찾을 수 없거나 로컬 API가 실패하면 역지오코딩은 원래 위치를 반환한다. [카카오 로컬 REST API](https://developers.kakao.com/docs/ko/local/dev-guide)
- HTTP/MySQL에서 사용자 격리, 중복·반복 가져오기, 비공개 충전소, 페이지, 설정의 부분 수정과 프로필 호환을 검증한다. 카카오 계약과 실패 처리는 루프백에서 검증한다. 실제 카카오 로컬 키와 Android 화면 연동은 운영 검증 대상이다.
- Java 25·MySQL 8.4에서 `mvnw.cmd clean verify` 전체 75개 통과, 실패·건너뜀 0개. 서로 다른 설정 필드의 동시 수정도 확인했다.
- [PR CI](https://github.com/YU-AI-Service-Project/Backend/actions/runs/45525101965)에서 자동 검증을 확인했다.

## 범주 H 검증과 인계

- 원본 API 명세의 52개 Method·URI를 아래 표와 대조했다. Spring Boot 소유 50개는 실행 중인 앱의 OpenAPI와 비교한다. 47·48번 FastAPI 경로는 AI 배포와 별도로 검증한다.
- Java 25·MySQL 8.4에서 `mvnw.cmd clean verify` 전체 91개 통과, 실패·건너뜀 0개. 실제 HTTP 앱에서 Swagger UI, 50개 경로, 운영 인증을 검증했고 루프백 및 MySQL에서 신고·수집·AI 상태 응답을 확인했다.
- 요일·시간대 패턴은 현지 날짜 3개 이상의 완전한 지역 상태 수집 표본을 사용한다. 회전율은 관측 간격 75분 이하의 구간을 합산하며, 관측 시간 24시간과 현지 날짜 2일이 필요하다. 12번은 운영 예측과 실제 도착 시점 상태를 연결한 표본이 확보될 때까지 `UNVERIFIED_MODEL`을 반환한다.
- 지역 요약에서는 가장 가까운 최대 30개 충전소를 평가한다. 현재 상태를 확인한 후보 수와 유효한 ETA 예측을 받은 후보 수를 따로 집계한다. 높은 가용성 기준 0.75는 검토용이며 응답에 포함한다.
- Flyway V9는 공지와 신고, V10은 수동 수집 요청을 추가한다. 신고에는 JWT가 필요하며 접수 정보는 회원 탈퇴 시 삭제한다. 공지 작성 권한, 신고 열람·보관 절차 및 비회원 접수 정책은 운영 전에 확정한다.
- 내부 경로는 기본적으로 루프백 연결과 별도의 Bearer 토큰을 요구한다. 수동 수집은 쓰기 토큰과 활성화 설정이 필요하다. 역방향 프록시의 접근 통제와 FastAPI 상태 응답 계약은 배포 환경에서 확인한다.
- 실제 공단·카카오·FastAPI 호출, SMTP, Android 실기기, 운영 데이터 규모의 부하 검증은 배포 환경을 확보한 후 진행한다.

## API 52개 추적표

기존 계약의 분류와 경로를 보존한다. 코드·테스트가 추가될 때 적용 엔드포인트와 증거를 갱신한다.

| # | 기준 Method·URI | 기능 | 명세 분류 | 범주 | 상태 | 적용·소스·테스트 |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | `GET /api/charging-stations` | 주변 충전소 목록·필터·정렬 | 핵심 | B | 구현됨 | [Controller](../../src/main/java/dev/wattup/backend/station/ChargingStationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/station/ChargingStationApiTests.java) |
| 2 | `GET /api/charging-stations/{stationId}` | 충전소 상세 | 핵심 | B | 구현됨 | [Controller](../../src/main/java/dev/wattup/backend/station/ChargingStationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/station/ChargingStationApiTests.java) |
| 3 | `GET /api/charging-stations/{stationId}/chargers` | 충전기별 구성·상태 목록 | 핵심 | B | 구현됨 | [Controller](../../src/main/java/dev/wattup/backend/station/ChargingStationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/station/ChargingStationApiTests.java) |
| 4 | `GET /api/charging-stations/{stationId}/status` | 최신 상태·가용 대수·갱신 시각 | 핵심 | B | 구현됨 | [Controller](../../src/main/java/dev/wattup/backend/station/ChargingStationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/station/ChargingStationApiTests.java) |
| 5 | `GET /api/charging-stations/{stationId}/prediction` | 충전소 단건 예측 | 핵심 | C | ETA 구현 · 실연동 대기 | [Controller](../../src/main/java/dev/wattup/backend/prediction/PredictionController.java) · [HTTP 테스트](../../src/test/java/dev/wattup/backend/prediction/PredictionApiTests.java) |
| 6 | `POST /api/predictions/batch` | 지도/목록용 다중 충전소 예측 | 핵심 | C | ETA 구현 · 실연동 대기 | [Controller](../../src/main/java/dev/wattup/backend/prediction/PredictionController.java) · [HTTP 테스트](../../src/test/java/dev/wattup/backend/prediction/PredictionApiTests.java) |
| 7 | `GET /api/recommendations` | 최대 3곳 추천·추천 근거 | 핵심 | D | 구현 · 순위 기준 검토 중 | [Controller](../../src/main/java/dev/wattup/backend/station/RecommendationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/station/ChargingStationApiTests.java) |
| 8 | `GET /api/locations/search` | 카카오 장소·주소 검색 프록시 | 추가 협의 | G | 구현 · 실연동 대기 | [Controller](../../src/main/java/dev/wattup/backend/location/LocationController.java) · [루프백 HTTP](../../src/test/java/dev/wattup/backend/location/LocationApiTests.java) |
| 9 | `GET /api/locations/reverse-geocode` | 선택 좌표의 주소 표현 | 추가 협의 | G | 구현 · 실연동 대기 | [Controller](../../src/main/java/dev/wattup/backend/location/LocationController.java) · [루프백 HTTP](../../src/test/java/dev/wattup/backend/location/LocationApiTests.java) |
| 10 | `GET /api/charging-stations/{stationId}/patterns` | 요일·시간대 혼잡 이력 | 확장 | H | 구현 · 표본 기준 적용 | [Controller](../../src/main/java/dev/wattup/backend/station/StationAnalyticsController.java) |
| 11 | `GET /api/charging-stations/{stationId}/turnover` | 관측 기준 회전율 | 확장 | H | 구현 · 표본 기준 적용 | [Controller](../../src/main/java/dev/wattup/backend/station/StationAnalyticsController.java) |
| 12 | `GET /api/charging-stations/{stationId}/prediction-quality` | 예측과 관측 결과 비교 | 확장 | H | 미검증 상태 응답 구현 · 평가 데이터 대기 | [Controller](../../src/main/java/dev/wattup/backend/station/StationAnalyticsController.java) |
| 13 | `GET /api/areas/charging-availability` | 주변 충전 여건 요약 | 확장 | H | 구현 · FastAPI 실연동 대기 | [Controller](../../src/main/java/dev/wattup/backend/station/StationAnalyticsController.java) |
| 14 | `GET /api/auth/agreements` | 가입에 필요한 약관·버전 안내 | 계정 협의 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 15 | `POST /api/auth/social/kakao` | 카카오 토큰 검증·기존 로그인/신규 가입 안내 | 계정 협의 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 16 | `POST /api/auth/social/google` | Google ID 토큰 검증·기존 로그인/신규 가입 안내 | 계정 협의 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 17 | `POST /api/auth/social/signup` | 최초 소셜 회원가입 확정 | 계정 협의 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 18 | `POST /api/auth/refresh` | Access Token 재발급·Refresh Token 회전 | 계정 협의 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 19 | `POST /api/auth/logout` | 현재 기기 로그아웃 | 계정 협의 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 20 | `POST /api/auth/logout-all` | 모든 기기 로그아웃 | 계정 협의 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 21 | `POST /api/auth/email/verifications` | 회원가입용 이메일 인증 메일 발송·재발송 | 이메일 선택 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 22 | `POST /api/auth/email/verifications/confirm` | 회원가입용 이메일 인증 코드 확인 | 이메일 선택 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 23 | `POST /api/auth/signup/email` | 일반 이메일 회원가입 | 이메일 선택 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 24 | `POST /api/auth/login/email` | 이메일·비밀번호 로그인 | 이메일 선택 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 25 | `POST /api/auth/password-resets` | 비밀번호 재설정 메일 발송 | 이메일 선택 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 26 | `POST /api/auth/password-resets/confirm` | 비밀번호 재설정 확정 | 이메일 선택 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 27 | `PATCH /api/users/me/password` | 로그인 후 비밀번호 변경 | 이메일 선택 | E | 구현 · 운영 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AuthController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 28 | `GET /api/users/me` | 내 정보 조회 | 계정 협의 | F | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 29 | `PATCH /api/users/me` | 닉네임·프로필 수정 | 계정 협의 | F | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 30 | `POST /api/users/me/reauthentication` | 중요한 계정 작업 전 본인 재확인 | 계정 협의 | F | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 31 | `DELETE /api/users/me` | 회원 탈퇴·세션 무효화·자료 정리 | 계정 협의 | F | 구현 · 운영 확인 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 32 | `GET /api/users/me/social-accounts` | 연결된 카카오·Google 계정 확인 | 계정 협의 | F | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 33 | `POST /api/users/me/social-accounts/{provider}` | 추가 소셜 계정 연결(선택) | 계정 협의 | F | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 34 | `DELETE /api/users/me/social-accounts/{provider}` | 소셜 계정 연결 해제(선택) | 계정 협의 | F | 구현 · 운영 확인 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 35 | `GET /api/users/me/agreements` | 내 약관 동의 상태 조회 | 계정 협의 | F | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 36 | `PATCH /api/users/me/agreements` | 선택 동의 수정 | 계정 협의 | F | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 37 | `GET /api/users/me/sessions` | 로그인 중인 기기/세션 목록(선택) | 계정 협의 | F | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 38 | `DELETE /api/users/me/sessions/{sessionId}` | 개별 세션 로그아웃(선택) | 계정 협의 | F | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/AccountController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 39 | `GET /api/users/me/favorites` | 즐겨찾기 목록 | 계정 협의 | G | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/PersonalizationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 40 | `POST /api/users/me/favorites` | 즐겨찾기 등록 | 계정 협의 | G | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/PersonalizationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 41 | `DELETE /api/users/me/favorites/{stationId}` | 즐겨찾기 해제 | 계정 협의 | G | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/PersonalizationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 42 | `POST /api/users/me/favorites/import` | 비회원 로컬 즐겨찾기 계정 이전(선택) | 계정 협의 | G | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/PersonalizationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 43 | `GET /api/users/me/preferences` | 저장된 검색 조건 조회 | 계정 협의 | G | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/PersonalizationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 44 | `PATCH /api/users/me/preferences` | 검색 반경·기본 충전기 조건 변경 | 계정 협의 | G | 구현 | [Controller](../../src/main/java/dev/wattup/backend/auth/PersonalizationController.java) · [HTTP/MySQL](../../src/test/java/dev/wattup/backend/auth/AuthApiTests.java) |
| 45 | `GET /api/app/notices` | 점검·장애 안내 | 추가 협의 | H | 구현 · 공지 운영 절차 대기 | [Controller](../../src/main/java/dev/wattup/backend/auth/AppSupportController.java) |
| 46 | `POST /api/support/reports` | 이용 오류·정보 오류 신고 | 추가 협의 | H | JWT 신고 구현 · 비회원 정책 보류 | [Controller](../../src/main/java/dev/wattup/backend/auth/AppSupportController.java) |
| 47 | `POST /internal/ai/predictions/fixed30/batch` | Spring Boot → AI 고정 30분 예측(HTTP 어댑터 제안) | 내부 | H | FastAPI 담당 | AI 팀 HTTP 계약 대기 |
| 48 | `POST /internal/ai/predictions/arrival/batch` | Spring Boot → AI ETA v4 배치 예측(기존 협업 규격) | 내부/확장 | C | Spring Boot 클라이언트 구현 · FastAPI 실연동 대기 | [클라이언트](../../src/main/java/dev/wattup/backend/prediction/EtaBatchClient.java) · [계약 테스트](../../src/test/java/dev/wattup/backend/prediction/EtaBatchClientTests.java) |
| 49 | `GET /internal/collector/health` | 상태 수집기 최근 성공·실패 현황 | 내부 | H | 구현 · 내부 배포 검증 대기 | [Controller](../../src/main/java/dev/wattup/backend/collection/CollectorOperationsController.java) |
| 50 | `GET /internal/collector/runs` | 수집 실행 이력 | 내부 | H | 구현 | [Controller](../../src/main/java/dev/wattup/backend/collection/CollectorOperationsController.java) |
| 51 | `POST /internal/collector/runs` | 관리자 수동 동기화 요청(선택) | 내부 | H | 구현 · 기본 비활성 | [Controller](../../src/main/java/dev/wattup/backend/collection/CollectorOperationsController.java) |
| 52 | `GET /internal/ai/health` | AI 서버 연동 상태 | 내부 | H | 구현 · FastAPI 실연동 대기 | [Controller](../../src/main/java/dev/wattup/backend/prediction/PredictionHealthController.java) |
