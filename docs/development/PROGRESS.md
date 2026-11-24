# WattUp 백엔드 개발 현황

## 작업 기준

- 기존 Docs 원본, API 명세 v3.0, DB 설계 및 ETA v4 ZIP과 멘토링 3회차 기록을 기준으로 사용한다. 화면 API 통합과 Android 정렬은 변경 제안으로 유지하고 기존 서버 Top-3 계약은 유지한다.
- 초기 개발 환경·MySQL Flyway V1·CI는 PR [#1](https://github.com/YU-AI-Service-Project/Backend/pull/1)의 병합 커밋 `6ff8050`을 기준으로 재사용한다.
- 현재 범주 A · `feat/data-collection-clean`. 공단 HTTP 클라이언트, 메타데이터·상태 적재, 실행 이력, 중복 실행 방지, 재시도·일 한도와 스케줄링을 구현했다. [GitHub PR 검색](https://github.com/YU-AI-Service-Project/Backend/pulls?q=is%3Apr+head%3Afeat%2Fdata-collection-clean)
- 범주 A는 수집 내부 서비스에 집중한다. 관리자 HTTP 49–51은 인증·운영 정책과 함께 H에서 제공한다. 공단 실연동은 인증키와 실호출 승인 후 검증한다.
- FastAPI 추론 경로 47·48의 소유자는 AI 서버다. Spring Boot는 계약 및 호출 클라이언트를 담당한다. 52번 AI 연결 상태는 Spring Boot 소유다.
- 범주 A의 병합이 승인 대기일 경우 그 상태를 기록한다. 같은 기능의 PR을 중복 생성하지 않는다.

## 범주 A 검증과 인계

- `7ef1ba4` HTTP 계약·API 추적표, `535363d` 상태 이력과 적재, 후속 커밋 지역 인벤토리 정합성과 스케줄링.
- Flyway V2는 일 한도와 지연 관측 구분, V3는 지역별 인벤토리 수집 실행을 추가한다. V1은 기존 파일을 사용한다.
- Java 25·MySQL 8.4에서 `mvnw.cmd clean verify` 성공. 로컬 15개 통과, 실패·건너뜀 0개. 공단 인증키와 실호출 승인 확인 전까지 실제 API 및 대량 적재 처리 시간은 미검증 상태다.
- 실행 중 수집기 영구 중단이 있으면 다음 정상 실행이 미완료 실행을 종료한다. 상태 집계와 향후 AI 특징 생성은 완전한 수집 실행과 최신 상태에 반영된 관측을 기준으로 한다.
- 병합과 CI 결과는 GitHub PR을 기준으로 확인한다. 범주 B는 최신 `main`에서 비회원 주변 검색, 상세, 충전기 목록, 현재 상태 순으로 구현한다.

## API 52개 추적표

기존 계약의 분류와 경로를 보존한다. 코드·테스트가 추가될 때 적용 엔드포인트와 증거를 갱신한다.

| # | 기준 Method·URI | 기능 | 명세 분류 | 범주 | 상태 | 적용·소스·테스트 |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | `GET /api/charging-stations` | 주변 충전소 목록·필터·정렬 | 핵심 | B | 미구현 | — |
| 2 | `GET /api/charging-stations/{stationId}` | 충전소 상세 | 핵심 | B | 미구현 | — |
| 3 | `GET /api/charging-stations/{stationId}/chargers` | 충전기별 구성·상태 목록 | 핵심 | B | 미구현 | — |
| 4 | `GET /api/charging-stations/{stationId}/status` | 최신 상태·가용 대수·갱신 시각 | 핵심 | B | 미구현 | — |
| 5 | `GET /api/charging-stations/{stationId}/prediction` | 충전소 단건 예측 | 핵심 | C | 미구현 | — |
| 6 | `POST /api/predictions/batch` | 지도/목록용 다중 충전소 예측 | 핵심 | C | 미구현 | — |
| 7 | `GET /api/recommendations` | 최대 3곳 추천·추천 근거 | 핵심 | D | 미구현 | — |
| 8 | `GET /api/locations/search` | 카카오 장소·주소 검색 프록시 | 추가 협의 | G | 미구현 | — |
| 9 | `GET /api/locations/reverse-geocode` | 선택 좌표의 주소 표현 | 추가 협의 | G | 미구현 | — |
| 10 | `GET /api/charging-stations/{stationId}/patterns` | 요일·시간대 혼잡 이력 | 확장 | H | 미구현 | — |
| 11 | `GET /api/charging-stations/{stationId}/turnover` | 관측 기준 회전율 | 확장 | H | 미구현 | — |
| 12 | `GET /api/charging-stations/{stationId}/prediction-quality` | 예측과 관측 결과 비교 | 확장 | H | 미구현 | — |
| 13 | `GET /api/areas/charging-availability` | 주변 충전 여건 요약 | 확장 | H | 미구현 | — |
| 14 | `GET /api/auth/agreements` | 가입에 필요한 약관·버전 안내 | 계정 협의 | E | 미구현 | — |
| 15 | `POST /api/auth/social/kakao` | 카카오 토큰 검증·기존 로그인/신규 가입 안내 | 계정 협의 | E | 미구현 | — |
| 16 | `POST /api/auth/social/google` | Google ID 토큰 검증·기존 로그인/신규 가입 안내 | 계정 협의 | E | 미구현 | — |
| 17 | `POST /api/auth/social/signup` | 최초 소셜 회원가입 확정 | 계정 협의 | E | 미구현 | — |
| 18 | `POST /api/auth/refresh` | Access Token 재발급·Refresh Token 회전 | 계정 협의 | E | 미구현 | — |
| 19 | `POST /api/auth/logout` | 현재 기기 로그아웃 | 계정 협의 | E | 미구현 | — |
| 20 | `POST /api/auth/logout-all` | 모든 기기 로그아웃 | 계정 협의 | E | 미구현 | — |
| 21 | `POST /api/auth/email/verifications` | 회원가입용 이메일 인증 메일 발송·재발송 | 이메일 선택 | E | 미구현 | — |
| 22 | `POST /api/auth/email/verifications/confirm` | 회원가입용 이메일 인증 코드 확인 | 이메일 선택 | E | 미구현 | — |
| 23 | `POST /api/auth/signup/email` | 일반 이메일 회원가입 | 이메일 선택 | E | 미구현 | — |
| 24 | `POST /api/auth/login/email` | 이메일·비밀번호 로그인 | 이메일 선택 | E | 미구현 | — |
| 25 | `POST /api/auth/password-resets` | 비밀번호 재설정 메일 발송 | 이메일 선택 | E | 미구현 | — |
| 26 | `POST /api/auth/password-resets/confirm` | 비밀번호 재설정 확정 | 이메일 선택 | E | 미구현 | — |
| 27 | `PATCH /api/users/me/password` | 로그인 후 비밀번호 변경 | 이메일 선택 | E | 미구현 | — |
| 28 | `GET /api/users/me` | 내 정보 조회 | 계정 협의 | F | 미구현 | — |
| 29 | `PATCH /api/users/me` | 닉네임·프로필 수정 | 계정 협의 | F | 미구현 | — |
| 30 | `POST /api/users/me/reauthentication` | 중요한 계정 작업 전 본인 재확인 | 계정 협의 | F | 미구현 | — |
| 31 | `DELETE /api/users/me` | 회원 탈퇴·세션 무효화·자료 정리 | 계정 협의 | F | 미구현 | — |
| 32 | `GET /api/users/me/social-accounts` | 연결된 카카오·Google 계정 확인 | 계정 협의 | F | 미구현 | — |
| 33 | `POST /api/users/me/social-accounts/{provider}` | 추가 소셜 계정 연결(선택) | 계정 협의 | F | 미구현 | — |
| 34 | `DELETE /api/users/me/social-accounts/{provider}` | 소셜 계정 연결 해제(선택) | 계정 협의 | F | 미구현 | — |
| 35 | `GET /api/users/me/agreements` | 내 약관 동의 상태 조회 | 계정 협의 | F | 미구현 | — |
| 36 | `PATCH /api/users/me/agreements` | 선택 동의 수정 | 계정 협의 | F | 미구현 | — |
| 37 | `GET /api/users/me/sessions` | 로그인 중인 기기/세션 목록(선택) | 계정 협의 | F | 미구현 | — |
| 38 | `DELETE /api/users/me/sessions/{sessionId}` | 개별 세션 로그아웃(선택) | 계정 협의 | F | 미구현 | — |
| 39 | `GET /api/users/me/favorites` | 즐겨찾기 목록 | 계정 협의 | G | 미구현 | — |
| 40 | `POST /api/users/me/favorites` | 즐겨찾기 등록 | 계정 협의 | G | 미구현 | — |
| 41 | `DELETE /api/users/me/favorites/{stationId}` | 즐겨찾기 해제 | 계정 협의 | G | 미구현 | — |
| 42 | `POST /api/users/me/favorites/import` | 비회원 로컬 즐겨찾기 계정 이전(선택) | 계정 협의 | G | 미구현 | — |
| 43 | `GET /api/users/me/preferences` | 저장된 검색 조건 조회 | 계정 협의 | G | 미구현 | — |
| 44 | `PATCH /api/users/me/preferences` | 검색 반경·기본 충전기 조건 변경 | 계정 협의 | G | 미구현 | — |
| 45 | `GET /api/app/notices` | 점검·장애 안내 | 추가 협의 | H | 미구현 | — |
| 46 | `POST /api/support/reports` | 이용 오류·정보 오류 신고 | 추가 협의 | H | 미구현 | — |
| 47 | `POST /internal/ai/predictions/fixed30/batch` | Spring Boot → AI 고정 30분 예측(HTTP 어댑터 제안) | 내부 | H | FastAPI 담당 | AI 팀 HTTP 계약 대기 |
| 48 | `POST /internal/ai/predictions/arrival/batch` | Spring Boot → AI ETA v4 배치 예측(기존 협업 규격) | 내부/확장 | C | FastAPI 담당 | AI 팀 HTTP 계약 대기 |
| 49 | `GET /internal/collector/health` | 상태 수집기 최근 성공·실패 현황 | 내부 | H | 미구현 | — |
| 50 | `GET /internal/collector/runs` | 수집 실행 이력 | 내부 | H | 미구현 | — |
| 51 | `POST /internal/collector/runs` | 관리자 수동 동기화 요청(선택) | 내부 | H | 미구현 | — |
| 52 | `GET /internal/ai/health` | AI 서버 연동 상태 | 내부 | H | 미구현 | — |
