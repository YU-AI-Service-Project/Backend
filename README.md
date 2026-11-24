# WattUp Backend

WattUp의 Spring Boot 서버입니다.

## 개발 요구사항

- Java 25
- Spring Boot 4.0.8
- Maven 3.9.16 (Maven Wrapper 사용)
- MySQL 8.4 LTS

`JAVA_HOME`을 Java 25 설치 경로로 설정합니다. 첫 실행에는 Maven 배포본과 의존성 다운로드가 필요합니다.

## 개발 데이터베이스

MySQL 관리자 계정으로 개발용 데이터베이스와 해당 스키마의 개발 계정을 생성합니다. 통합 테스트를 실행할 경우 테스트용 스키마도 생성합니다. MySQL이 임의로 생성한 비밀번호를 로컬에서 보관하고, 저장소에는 넣지 않습니다.

```sql
CREATE DATABASE wattup CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE wattup_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'wattup'@'127.0.0.1' IDENTIFIED BY RANDOM PASSWORD;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON wattup.* TO 'wattup'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON wattup_test.* TO 'wattup'@'127.0.0.1';
```

| 환경 변수 | 설정 |
| --- | --- |
| `WATTUP_DB_URL` | 기본값은 `jdbc:mysql://127.0.0.1:3307/wattup?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`입니다. MySQL이 3306에서 실행 중이면 URL의 포트를 변경합니다. |
| `WATTUP_DB_USER` | 기본값은 `wattup`입니다. |
| `WATTUP_DB_PASSWORD` | 서버 실행과 MySQL 통합 테스트에 필요한 개발 DB 비밀번호입니다. |
| `WATTUP_TEST_DB_URL` | 통합 테스트를 실행할 때 `wattup_test` 스키마의 JDBC URL을 지정합니다. |

일반 빌드와 애플리케이션 컨텍스트 테스트에는 DB가 필요하지 않습니다. MySQL 통합 테스트는 `WATTUP_TEST_DB_URL`을 설정하면 실행되며 지정한 테스트 DB에 Flyway 마이그레이션을 적용합니다.

## 빌드와 실행

| 작업 | Windows PowerShell | macOS / Linux |
| --- | --- | --- |
| 빌드 및 테스트 | `.\mvnw.cmd verify` | `./mvnw verify` |
| 개발 서버 실행 | `.\mvnw.cmd spring-boot:run` | `./mvnw spring-boot:run` |

서버는 시작할 때 `src/main/resources/db/migration`의 Flyway 마이그레이션을 자동으로 검증하고 적용합니다. V1은 충전소·충전기, 수집 실행·페이지, 상태 관측·최신 상태·변화 이력을 생성합니다.

수집기 구현 시 공공 API의 `nowTsdt`를 UTC로 변환해 `charge_started_at`에 저장하고 관측 지문에 반영합니다. 동일 상태 코드에서 충전 시작 시각이 바뀐 응답도 관측으로 보존할 수 있습니다. 수집 실행의 완료 시각과 페이지 결과는 ETA 특징 생성 시 관측을 사용할 수 있는 시점을 판단하는 데 쓰입니다.

## 공공데이터 수집

수집은 기본적으로 비활성화되어 있습니다. 실호출이 승인된 환경에서만 아래 변수를 설정합니다.

| 환경 변수 | 값 |
| --- | --- |
| `WATTUP_KECO_ENABLED` | 수집 활성화 시 `true` |
| `WATTUP_KECO_SERVICE_KEY` | 공공데이터포털의 디코딩된 인증키 |
| `WATTUP_KECO_REGION` | 승인된 두 자리 시·도 코드 |
| `WATTUP_KECO_PAGE_SIZE` | 페이지당 10–9999, 기본값 9999 |
| `WATTUP_KECO_DAILY_LIMIT` | 이 DB를 사용하는 수집기의 일 요청 상한, 기본값 1000 |

활성화하면 시작 시와 전체 수집 완료 1시간 후에 `getChargerInfo`를 호출해 메타데이터와 전체 상태를 함께 반영합니다. 최초 전체 수집 성공 후에는 매 5분에 `getChargerStatus`를 최근 10분 조건으로 호출합니다. 수집 잠금과 JDBC 작업에 연결을 각각 사용하므로 풀에는 최소 2개 연결이 필요합니다.

페이지의 원천 건수·번호·ID 중복을 확인한 뒤 한 트랜잭션으로 반영합니다. 일 한도는 한국 시각 기준이며, 재시도도 포함합니다. 늦거나 수집 시각보다 미래인 원천 상태는 이력에 남기고 최신 상태 갱신에서 제외합니다. Flyway V2는 일 요청 카운터와 관측의 최신 상태 반영 여부를 추가합니다. V3는 지역별 전체 스냅샷에서 빠진 충전기를 비활성화할 수 있도록 마지막 메타데이터 수집 실행을 기록합니다. 이전 관측과 충전기 기록은 보존합니다.

테스트에서는 루프백 HTTP 서버의 응답과 격리 MySQL을 사용합니다. 수집기가 실제 공단 API를 호출하도록 설정하려면 [공공데이터포털 서비스](https://www.data.go.kr/data/15076352/openapi.do)의 권한과 호출 한도를 확인해야 합니다.

## 비회원 충전소 조회

| 경로 | 응답과 파라미터 |
| --- | --- |
| `GET /api/charging-stations` | 필수 `latitude`, `longitude`. 선택 `radiusM`(기본 3000, 범위 100–10000), `limit`(기본 30, 범위 1–100), `profile`, 반복 `chargerTypes`, `minOutputKw`, `availableOnly`. `sort`는 `DISTANCE`를 지원한다. `total`은 결과 제한 전의 충전소 수다. |
| `GET /api/charging-stations/{stationId}` | 기본 정보와 충전기 목록, 상태 집계를 함께 반환한다. 선택 `profile`. |
| `GET /api/charging-stations/{stationId}/chargers` | 충전기 목록. 선택 `profile`, `typeCode`, `minOutputKw`. |
| `GET /api/charging-stations/{stationId}/status` | 상태만 갱신할 때 사용한다. 선택 `profile`. |

예시 요청은 `/api/charging-stations?latitude=35.834&longitude=128.751&profile=DC_COMBO&minOutputKw=50`이다. 프로필은 기본 `ALL`, `AC_SLOW`, `DC_COMBO`를 지원한다. 프로필, 두 자리 원천 종류 코드, 최소 출력은 각각 충족해야 한다. `minOutputKw`는 0–999999.99 kW 범위다. 제한·삭제된 충전소와 충전기는 검색에서 제외한다.

충전소의 `asOf`는 선택된 충전기 중 가장 오래된 확인 시각이다. 확인 시각 자체가 없으면 `null`을 반환한다. 10분 이상 된 대수는 마지막 관측값으로 남기고 `STALE`로 표시한다. 확인된 지연이 없지만 관측 이력이 없는 충전기가 있으면 `UNKNOWN`을 표시한다. `availableOnly`는 10분 이내 가용 충전기가 한 대 이상 확인된 충전소를 선택한다.

## 도착 시점 예측

| 경로 | 요청 |
| --- | --- |
| `GET /api/charging-stations/{stationId}/prediction` | 쿼리 `profile`, `originLatitude`, `originLongitude` |
| `POST /api/predictions/batch` | JSON 예시 `{"profile":"DC_COMBO","stationIds":["KECO:DEMO"],"origin":{"latitude":35.83,"longitude":128.75}}` |

`mode`의 기본값은 `ARRIVAL_ETA`다. 배치는 고유 충전소 ID를 최대 30개 받고 입력 순서대로 결과를 돌려준다. `FIXED_30M`은 별도 모델이 필요하므로 현재 `503 MODEL_UNAVAILABLE`을 반환한다.

| 환경변수 | 용도 |
| --- | --- |
| `WATTUP_KAKAO_REST_API_KEY` | 카카오모빌리티 자동차 길찾기 REST 키 |
| `WATTUP_AI_ETA_BATCH_URL` | AI 팀과 합의한 전체 배치 URL. API 명세의 경로는 `/internal/ai/predictions/arrival/batch`다. |
| `WATTUP_AI_ETA_BEARER_TOKEN` | 배치 서버에 Bearer 인증이 필요한 경우 사용하는 토큰 |

Spring Boot는 수집이 완료된 MySQL 이력으로 30분 특징을 만들고, 카카오에서 도로 ETA를 조회한 후 FastAPI에 한 번에 전달한다. FastAPI의 `OK`는 항목 ID, 충전소, 프로필, 입력 시각과 계산된 예측 시각이 일치할 때만 사용한다. 응답의 `requestedArrivalAt`은 ETA 기준 도착 예정 시각이며 `forecastAt`은 5분 격자상의 모델 예측 시각이다. 예측할 수 없는 항목은 이유와 `probability: null`을 반환한다.

상태 수집이 450초 넘게 지연되거나, 30분 관측을 확인할 수 없거나, 예측 범위를 벗어나면 모델을 호출하지 않는다. 카카오 호출은 서버 인스턴스당 동시 8개, 요청당 최대 30개이며 경로 조회 전체에 9초 제한을 둔다. FastAPI 호출은 시도당 2초이며 일시적 장애에서 한 번 재시도한다. 운영 키와 실제 FastAPI 배포의 통합 검증은 별도로 진행한다.

## 충전소 추천

`GET /api/recommendations`는 `latitude`, `longitude`, `profile`을 받는다. `radiusM` 기본값은 3000m다. `minOutputKw`로 출력 조건을 지정할 수 있고 `limit`은 1–3이다. 모드 기본값은 `ARRIVAL_ETA`다.

`items`에는 순위, 현재 충전기 대수와 최신성, 확인된 경우 도로 거리와 ETA, 예측 확률과 추천 근거가 들어간다. `totalCandidates`는 반경과 필터에 맞는 전체 충전소 수이며, `evaluatedCandidates`는 순위를 계산한 후보 수다. `WATTUP_RECOMMENDATION_CANDIDATE_LIMIT`의 기본값은 10이고 배포 설정 범위는 1–30이다.

현재 가용 대수에는 마지막 확인값을 담고, 순위에는 최근 10분 안에 가용으로 확인된 대수만 사용한다. 예측이 없는 후보가 있으면 모든 후보를 현재 상태·거리·출력 기준으로 정렬한다. 확인한 확률은 그대로 반환하고, 예측이 불가능한 후보의 `probability`는 `null`이다. 최소 출력 때문에 모델이 예측하는 충전기 집합이 달라지면 해당 후보의 확률도 `null`이다. 카카오에서 유효한 경로를 받으면 이력이나 AI 결과가 없어도 도로 거리와 ETA를 제공한다. 순위의 검토용 기준은 [개발 현황](docs/development/PROGRESS.md#범주-d-검증과-인계)에 기록했다.

## 계정과 인증

카카오·Google 인증 후 가입 이력이 있으면 WattUp 토큰을 반환한다. 최초 인증에는 10분짜리 가입 티켓을 반환한다. 이메일 가입은 메일로 받은 코드를 확인하고 10분짜리 가입 티켓을 발급받는 순서다. 두 방식 모두 서버에 게시된 최신 필수 약관에 동의해야 가입할 수 있다. 약관 버전·문서 URL은 운영 정책에 맞게 `agreement_policies`에 등록한다.

| 환경변수 | 용도 |
| --- | --- |
| `WATTUP_AUTH_JWT_SECRET` | 32바이트 이상의 별도 무작위 키를 Base64로 인코딩한 JWT 서명 키 |
| `WATTUP_AUTH_EMAIL_SECRET` | 별도로 생성한 32바이트 이상 Base64 이메일 코드 검증 키 |
| `WATTUP_AUTH_KAKAO_APP_ID` | 카카오 토큰에서 기대하는 숫자 앱 ID |
| `WATTUP_AUTH_GOOGLE_CLIENT_ID` | Google ID 토큰에서 기대하는 서버 OAuth 클라이언트 ID |
| `WATTUP_AUTH_MAIL_FROM` | SMTP 발신 주소 |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | STARTTLS를 지원하는 SMTP 연결 설정. SMTP 계정 인증을 사용하는 서버는 `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true`를 설정한다. |

액세스 JWT는 한 시간, 갱신 토큰은 발급 후 30일 동안 유효하다. 갱신마다 새 토큰을 발급하며, 유효기간이 남은 이전 갱신 토큰을 다시 제출하면 해당 기기의 세션 전체를 차단한다. 앱은 같은 갱신 토큰을 동시에 보내거나 자동 재시도하지 않도록 갱신 요청을 직렬화한다. 현재 기기 로그아웃에는 Bearer JWT와 같은 세션의 갱신 토큰이 필요하다. 전체 로그아웃과 비밀번호 변경·재설정은 모든 기기에서 기존 JWT와 갱신 토큰을 무효화한다.

이메일 코드는 10분 안에 사용해야 하며 같은 코드의 입력은 5회까지 허용한다. 재발송 시 이전 가입 코드는 폐기한다. 비밀번호 재설정 링크용 토큰은 15분 안에 사용할 수 있고, 비밀번호가 갱신되면 남은 재설정 토큰도 폐기한다. 검색, 상태 조회, 예측, 추천은 비회원도 사용할 수 있다.
