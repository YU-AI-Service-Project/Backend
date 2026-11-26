# 도착 가용 예측 v9 연동

v9의 추가 변수 9개는 기존 MySQL 테이블에서 읽습니다. [AI-Server의 main 실행 가이드](https://github.com/YU-AI-Service-Project/AI-Server/blob/main/README.md)를 따라 모델 서버를 띄우고, `GET /health/ready`가 `UP`과 `wattup-eta-v9-20261126`을 반환하는지 확인합니다. FastAPI와 Spring Boot를 같은 호스트에서 실행하는 경우 백엔드에 아래 값을 설정하고 다시 시작합니다.

| 이름 | 설정 |
|---|---|
| `WATTUP_AI_ETA_BATCH_URL` | `http://127.0.0.1:8000/internal/v3/arrival-predictions/batch` |
| `WATTUP_AI_ETA_FEATURE_VERSION` | `daegu-eta-v9-events-37` |
| `WATTUP_AI_ETA_BEARER_TOKEN` | AI-Server의 `WATTUP_INTERNAL_API_KEY`와 같은 비밀 변수 |
| `WATTUP_AI_HEALTH_URL` | `http://127.0.0.1:8000/health/ready` |
| `WATTUP_KAKAO_REST_API_KEY` | 기존 자동차 길찾기 키 |

기본 입력 버전은 `daegu-eta-v4`입니다. v9를 선택할 때는 URL과 버전을 같이 설정하세요. 다른 서버의 AI에 토큰을 전송할 때는 배치 URL에 HTTPS를 사용합니다. 유효 후보가 10개를 넘는 요청은 Java 클라이언트가 내부 AI 요청을 10개 이하로 나누고 응답을 합칩니다.

## DB 값의 출처

- `EtaHistoryRepository`가 `charger_status_observations`와 `collection_runs`로 5분 단위 관측을 읽습니다. 관측과 수집이 완료된 시각은 UTC입니다.
- `charger_state_changes`의 최근 15분 가용 변화와 최근 30분 충전 상태 변화를 최초 인벤토리 관측과 구분합니다. 완전한 수집 실행의 관측으로 연결되는 행으로 집계합니다.
- `EtaFeatures`가 현재 빈 충전기 상태의 지속시간과 충전 중 장치의 세션 경과를 계산합니다. 상태를 확인할 수 없는 충전기가 있거나 30분 이력이 부족하면 해당 후보는 확률을 추정하지 않습니다.

마이그레이션은 기존 Flyway 파일을 사용합니다. 백엔드 로컬 DB 환경은 [로컬 실행 가이드](SETUP.md)를 참고하세요.

## 앱이 사용하는 경로

Top-3 순위는 Spring Boot가 계산합니다. 앱은 `GET /api/recommendations`에 좌표와 `profile`, `mode=ARRIVAL_ETA`, `limit=3`을 보내고 `items[].rank` 순서로 표시합니다. `current`에서 현재 상태를, `etaSeconds`와 `prediction.probability`에서 도로 ETA와 도착 가용 확률을 읽습니다.

`GET /api/charging-stations`와 `POST /api/predictions/batch`는 주변 목록과 후보별 예측을 따로 조회할 때 사용할 수 있습니다. FastAPI 입력과 결과의 상세 계약은 [AI-Server DB·API 가이드](https://github.com/YU-AI-Service-Project/AI-Server/blob/main/docs/backend-db-integration.md)를 참고합니다.

## Spring에서 연결 확인

1. `WATTUP_OPENAPI_ENABLED=true`로 실행한 Spring Swagger에서 단건 예측, 배치 예측, Top-3를 차례로 요청합니다.
2. 예측 가능한 후보의 `prediction.status`가 `OK`이고, `prediction.modelVersion`이 `wattup-eta-v9-20261126`이며, `prediction.probability`가 0 이상 1 이하인지 확인합니다. Top-3에서는 Spring이 계산한 `rank`도 확인합니다.
3. 상태 이력이 부족하면 `INPUT_INCOMPLETE`, 관측이 오래됐으면 `STALE_FEATURES`, 경로 ETA가 없으면 `ROUTE_UNAVAILABLE`이 표시될 수 있습니다. 운영용 상태 조회 `GET /internal/ai/health`는 내부 읽기 토큰이 필요하며, 유효한 추론이 최근에 성공한 뒤 `HEALTHY`가 됩니다.

## 테스트

```powershell
.\mvnw.cmd test
```

`WATTUP_TEST_DB_URL`을 별도 `wattup_test` 스키마로 지정하면 MySQL 이력 테스트가 실행됩니다. 실제 v9 서버와의 연동 테스트에는 추가로 `WATTUP_TEST_AI_V9_URL`에 예측 URL, `WATTUP_TEST_AI_V9_TOKEN`에 테스트 토큰, `WATTUP_TEST_AI_V9_REQUEST`에 AI-Server의 `example_ai_request.json` 경로를 지정합니다.
