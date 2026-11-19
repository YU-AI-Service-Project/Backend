# WattUp Backend

WattUp의 Spring Boot 서버입니다.

## 개발 요구사항

- Java 25
- Spring Boot 4.0.8
- Maven 3.9.16 (Maven Wrapper 사용)

`JAVA_HOME`을 Java 25 설치 경로로 설정합니다. 첫 실행에는 Maven 배포본과 의존성 다운로드가 필요합니다.

| 작업 | Windows PowerShell | macOS / Linux |
| --- | --- | --- |
| 빌드 및 테스트 | `.\mvnw.cmd verify` | `./mvnw verify` |
| 개발 서버 실행 | `.\mvnw.cmd spring-boot:run` | `./mvnw spring-boot:run` |
