# 도착 가용 예측 v9 연동

v9의 추가 변수 9개는 기존 MySQL 테이블에서 읽습니다. [AI-Server 연동 브랜치](https://github.com/YU-AI-Service-Project/AI-Server/tree/feat/eta-v9-handoff)에서 모델 서버와 예제 요청을 실행한 뒤, 백엔드 실행 환경에 아래 값을 설정합니다.

| 이름 | 설정 |
|---|---|
| `WATTUP_AI_ETA_BATCH_URL` | `http://127.0.0.1:8000/internal/v3/arrival-predictions/batch` |
| `WATTUP_AI_ETA_FEATURE_VERSION` | `daegu-eta-v9-events-37` |
| `WATTUP_AI_ETA_BEARER_TOKEN` | AI-Server의 `WATTUP_INTERNAL_API_KEY`와 같은 비밀 변수 |
| `WATTUP_AI_HEALTH_URL` | `http://127.0.0.1:8000/health/ready` |
| `WATTUP_KAKAO_REST_API_KEY` | 기존 자동차 길찾기 키 |

기본 입력 버전은 `daegu-eta-v4`입니다. v9를 선택할 때는 URL과 버전을 같이 설정하세요. 유효 후보가 10개를 넘는 기존 요청은 Java 클라이언트가 내부 AI 요청을 10개 이하로 나눠 응답을 합칩니다. 일반 앱 화면은 처음부터 후보를 최대 10개로 요청할 수 있습니다.

## DB 값의 출처

- `EtaHistoryRepository`가 `charger_status_observations`와 `collection_runs`로 5분 단위 관측을 읽습니다. 관측과 수집이 완료된 시각은 UTC입니다.
- `charger_state_changes`의 최근 15분 가용 변화와 최근 30분 충전 상태 변화를 최초 인벤토리 관측과 구분합니다. 완전한 수집 실행의 관측으로 연결되는 행으로 집계합니다.
- `EtaFeatures`가 현재 빈 충전기 상태의 지속시간과 충전 중 장치의 세션 경과를 계산합니다. 상태를 확인할 수 없는 충전기가 있거나 30분 이력이 부족하면 해당 후보는 확률을 추정하지 않습니다.

마이그레이션은 기존 Flyway 파일을 사용합니다. 백엔드 로컬 DB 환경은 [로컬 실행 가이드](SETUP.md)를 참고하세요.

## 앱이 사용하는 경로

1. `GET /api/charging-stations`의 `limit=10`, `availableOnly=false`, 동일한 `profile`로 주변 목록을 읽습니다.
2. 그 목록의 ID와 출발 위치를 `POST /api/predictions/batch`로 한 번 보냅니다. `items[].prediction`에서 확률과 도로 거리, ETA를 읽고 `stationId`로 화면 목록에 합칩니다.
3. 거리·확률 결합 순서가 필요하면 `2 * (roadDistanceM / 1000) + 15 * (1 - probability)`로 낮은 순서대로 표시합니다. 확률이 없는 후보도 현재 상태와 위치를 표시할 수 있습니다.

예측 응답과 추가 필드 전체는 [AI-Server DB·API 가이드](https://github.com/YU-AI-Service-Project/AI-Server/blob/feat/eta-v9-handoff/docs/backend-db-integration.md)에 있습니다.

## 테스트

```powershell
.\mvnw.cmd test
```

`WATTUP_TEST_DB_URL`을 별도 `wattup_test` 스키마로 지정하면 MySQL 이력 테스트가 실행됩니다. 실제 v9 서버와의 연동 테스트에는 추가로 `WATTUP_TEST_AI_V9_URL`에 예측 URL, `WATTUP_TEST_AI_V9_TOKEN`에 테스트 토큰, `WATTUP_TEST_AI_V9_REQUEST`에 AI-Server의 `example_ai_request.json` 경로를 지정합니다.
