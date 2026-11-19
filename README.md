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
