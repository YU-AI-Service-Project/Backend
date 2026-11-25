# WattUp 백엔드 로컬 실행 가이드

새 컴퓨터에서 저장소를 클론한 뒤 개발용 MySQL을 만들고 Spring Boot 서버를 실행합니다. 이 문서의 예제는 MySQL `3306`, Spring Boot `8080`을 사용합니다.

## 1. 프로그램 설치

- Git을 설치하고 WattUp 백엔드 저장소에 접근할 수 있는 GitHub 계정으로 인증합니다.
- JDK 25를 설치합니다. 설치 경로를 `JAVA_HOME`으로 지정하고 `java`가 해당 JDK를 사용하도록 PATH에 `JAVA_HOME/bin`을 추가합니다. [Temurin JDK 25](https://adoptium.net/temurin/releases/?package=jdk&version=25)
- MySQL Community Server 8.4 LTS를 설치하고 서버를 시작합니다. 설치 때 설정한 관리자 계정과 TCP 포트를 확인합니다. Windows에서는 MySQL Configurator 또는 설치 시 등록한 Windows 서비스에서 서버를 시작할 수 있습니다. 터미널에서 `mysql`을 찾지 못하면 MySQL의 `bin`을 PATH에 추가하거나 MySQL Workbench의 쿼리 창을 사용합니다. [MySQL 설치 가이드](https://dev.mysql.com/doc/refman/8.4/en/installing.html)

```text
git --version
java -version
mysql --version
```

Windows PowerShell에서 설치 경로를 직접 지정할 경우 아래 예제의 경로를 실제 JDK 폴더로 바꿉니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

macOS에서는 `export JAVA_HOME="$(/usr/libexec/java_home -v 25)"`로 설정할 수 있습니다. Linux에서는 설치한 JDK 25 디렉터리를 `JAVA_HOME`에 지정합니다. 두 환경 모두 `export PATH="$JAVA_HOME/bin:$PATH"`를 실행한 뒤 `java -version`이 25인지 확인합니다.

저장소에 포함된 Maven Wrapper가 Maven 3.9.16을 내려받습니다. 첫 빌드에는 Maven 배포본과 의존성을 받을 네트워크 연결이 필요합니다.

## 2. 저장소 클론

```bash
git clone https://github.com/YU-AI-Service-Project/Backend.git
cd Backend
git switch main
```

아래 Maven 명령은 `pom.xml`이 있는 저장소 루트에서 실행합니다.

## 3. 개발용 MySQL 생성

MySQL에 관리자 계정으로 접속합니다. 포트가 `3307`이면 아래 명령과 이후 JDBC URL에 그 값을 사용합니다. Linux에서 관리자 계정에 소켓 인증을 사용하는 경우 `sudo mysql`로 접속합니다.

```bash
mysql --host=127.0.0.1 --port=3306 --user=root --password
```

새 로컬 MySQL에서 아래 SQL을 한 번 실행합니다. `CREATE USER`가 출력한 비밀번호를 로컬 비밀번호 관리자에 보관합니다. 이미 계정을 만들었다면 기존 비밀번호를 사용하고 필요한 스키마와 권한을 확인합니다. [MySQL CREATE USER](https://dev.mysql.com/doc/refman/8.4/en/create-user.html)

```sql
CREATE DATABASE IF NOT EXISTS wattup
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'wattup'@'127.0.0.1' IDENTIFIED BY RANDOM PASSWORD;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON wattup.* TO 'wattup'@'127.0.0.1';
```

터미널의 MySQL 관리자 세션은 `exit`로 닫습니다. 이어서 애플리케이션용 계정과 생성한 비밀번호로 TCP 접속을 확인합니다. 아래 명령이 `wattup`을 출력하면 다음 단계로 진행합니다.

```bash
mysql --host=127.0.0.1 --port=3306 --user=wattup --password --database=wattup --execute="SELECT DATABASE();"
```

## 4. 환경 변수 설정과 서버 시작

`WATTUP_DB_PASSWORD`에는 3단계에서 만든 **개발용 DB 계정의 비밀번호**를 사용합니다. 프로젝트의 JDBC 기본값은 `3307`이므로 예제에서는 설치 포트 `3306`을 명시합니다. 환경 변수는 다음 명령을 실행하는 터미널에서 설정합니다. IntelliJ로 실행하면 Run Configuration의 **Environment variables**에 같은 값을 넣습니다. `.env`를 파일로 보관하는 경우 셸이나 IDE가 읽어 환경 변수로 전달하도록 설정합니다. 비밀번호와 API 키는 Git에 올리지 않습니다.

### Windows PowerShell

```powershell
$env:WATTUP_DB_URL = 'jdbc:mysql://127.0.0.1:3306/wattup?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:WATTUP_DB_USER = 'wattup'
$dbSecret = Read-Host 'MySQL wattup 계정 비밀번호' -AsSecureString
$env:WATTUP_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $dbSecret).Password
$env:WATTUP_OPENAPI_ENABLED = 'true'
$env:WATTUP_KECO_ENABLED = 'false'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8080'

.\mvnw.cmd spring-boot:run
```

### macOS 또는 Linux

```bash
export WATTUP_DB_URL='jdbc:mysql://127.0.0.1:3306/wattup?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export WATTUP_DB_USER='wattup'
printf 'MySQL wattup 계정 비밀번호 입력 후 Enter: '
read -rs WATTUP_DB_PASSWORD
printf '\n'
export WATTUP_DB_PASSWORD
export WATTUP_OPENAPI_ENABLED=true
export WATTUP_KECO_ENABLED=false
export SERVER_ADDRESS=127.0.0.1
export SERVER_PORT=8080

./mvnw spring-boot:run
```

첫 실행에서 Flyway가 저장소에 포함된 마이그레이션을 실행합니다. 테이블이 만들어지고 로그에 `Tomcat started on port 8080`과 `Started WattUpApplication`이 표시되면 서버가 준비된 상태입니다. 중지는 해당 터미널에서 `Ctrl+C`를 사용합니다.

## 5. Swagger와 서버 확인

Swagger UI와 OpenAPI는 프로젝트에 적용돼 있습니다. `WATTUP_OPENAPI_ENABLED=true`로 실행하면 열립니다. 기본값은 `false`입니다.

- 브라우저에서 Swagger UI를 엽니다. [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)
- OpenAPI JSON을 확인합니다. Spring Boot 담당 50개 API가 문서에 포함됩니다. [OpenAPI JSON](http://127.0.0.1:8080/v3/api-docs)
- Swagger에서 `GET /api/charging-stations` → **Try it out**을 선택하고 `latitude=35.8714`, `longitude=128.6014`로 요청합니다. HTTP `200`이면 서버와 DB가 연결된 상태입니다. 새 DB에는 충전소 데이터가 없으므로 `items`가 빈 목록으로 나올 수 있습니다.
- 회원 API를 호출하려면 Swagger의 **Authorize**에서 WattUp 액세스 토큰을 입력합니다. 입력창에는 `Bearer ` 접두사 없이 토큰만 붙여 넣습니다.

터미널에서 확인하려면 서버가 실행된 채로 새 터미널을 열고 아래 명령을 실행합니다. Windows PowerShell에서는 `curl.exe`를 사용합니다.

```bash
curl --fail-with-body 'http://127.0.0.1:8080/api/charging-stations?latitude=35.8714&longitude=128.6014'
```

## 6. 기능별 선택 환경 변수

서버 시작과 Swagger·비회원 조회는 위의 기본 설정으로 확인할 수 있습니다. 아래 값은 해당 기능이 필요할 때 실행 환경에 추가합니다.

| 기능 | 환경 변수 | 설정 |
| --- | --- | --- |
| 공단 데이터 수집 | `WATTUP_KECO_ENABLED`, `WATTUP_KECO_SERVICE_KEY`, `WATTUP_KECO_REGION` | 승인된 환경에서 `true`, 디코딩된 공단 인증키, 두 자리 시·도 코드를 설정합니다. 활성화 시 서버 시작과 함께 API를 호출합니다. |
| 공단 요청 설정 | `WATTUP_KECO_PAGE_SIZE`, `WATTUP_KECO_DAILY_LIMIT` | 기본값은 각각 `9999`, `1000`입니다. 승인된 요청 한도에 맞춥니다. |
| 경로·장소 검색 | `WATTUP_KAKAO_REST_API_KEY`, `WATTUP_KAKAO_LOCAL_REST_API_KEY` | 각각 카카오모빌리티 자동차 경로와 카카오 로컬 검색에 사용합니다. |
| ETA 예측 | `WATTUP_AI_ETA_BATCH_URL`, `WATTUP_AI_ETA_BEARER_TOKEN` | FastAPI 배치 전체 URL과 서버에서 인증을 요구할 경우 토큰을 설정합니다. 도착 예측에는 카카오 경로 키와 수집 이력도 필요합니다. |
| ETA 예측 품질 | `WATTUP_PREDICTION_QUALITY_CAPTURE_ENABLED` | 기본값 `false`. `true`로 설정하면 앞으로 검증되는 예측의 평가 표본을 저장합니다. |
| 추천 후보 수 | `WATTUP_RECOMMENDATION_CANDIDATE_LIMIT` | 기본값 `10`, 범위 `1–30`입니다. |
| 회원 토큰 | `WATTUP_AUTH_JWT_SECRET` | 무작위 32바이트 이상의 값을 Base64로 인코딩합니다. |
| 카카오·Google 로그인 | `WATTUP_AUTH_KAKAO_APP_ID`, `WATTUP_AUTH_GOOGLE_CLIENT_ID` | 사용하는 소셜 공급자의 앱 ID 또는 OAuth 클라이언트 ID를 설정합니다. 가입을 검증하려면 최신 필수 약관도 DB에 등록합니다. |
| 이메일 가입·재설정 | `WATTUP_AUTH_EMAIL_SECRET`, `WATTUP_AUTH_MAIL_FROM`, `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | JWT와 별개인 Base64 키와 STARTTLS를 지원하는 SMTP 정보를 설정합니다. SMTP 인증 설정은 [계정과 인증](../../README.md#계정과-인증)을 참고합니다. |
| 회원 탈퇴·카카오 연결 해제 | `WATTUP_ACCOUNT_WITHDRAWAL_ENABLED`, `WATTUP_AUTH_KAKAO_ADMIN_KEY` | 탈퇴는 기본적으로 꺼져 있습니다. 카카오 연결 해제에는 로그인에 사용하는 앱의 관리자 키가 필요합니다. |
| 내부 운영 API | `WATTUP_INTERNAL_ALLOWED_ADDRESSES`, `WATTUP_INTERNAL_READ_TOKEN`, `WATTUP_INTERNAL_WRITE_TOKEN`, `WATTUP_INTERNAL_MANUAL_COLLECTION_ENABLED` | 기본 접근 IP는 루프백입니다. 읽기와 쓰기에 서로 다른 43–128자리 Base64URL 형식 토큰을 사용합니다. 수동 수집은 기본적으로 꺼져 있습니다. |
| FastAPI 상태 조회 | `WATTUP_AI_HEALTH_URL`, `WATTUP_AI_HEALTH_TOKEN` | 별도로 제공되는 상태 URL과 필요한 경우 전용 토큰을 설정합니다. |

## 7. 통합 테스트 또는 JAR 실행

### MySQL 통합 테스트

3단계의 관리자 세션에서 통합 테스트 전용 스키마를 추가로 만듭니다.

```sql
CREATE DATABASE IF NOT EXISTS wattup_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON wattup_test.* TO 'wattup'@'127.0.0.1';
```

DB 환경 변수는 4단계와 동일하게 설정합니다. 테스트 URL의 스키마 이름은 `wattup_test`여야 합니다. `WATTUP_TEST_DB_URL`을 생략하면 MySQL 통합 테스트를 건너뜁니다.

```powershell
$env:WATTUP_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:3306/wattup_test?connectionTimeZone=UTC&forceConnectionTimeToSession=true'
.\mvnw.cmd clean verify
```

```bash
export WATTUP_TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/wattup_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
./mvnw clean verify
```

### 빌드한 JAR로 실행

4단계와 같은 환경 변수로 실행합니다. Maven 명령이 성공한 뒤 JAR을 실행합니다.

```powershell
.\mvnw.cmd -DskipTests package
java -jar target/wattup-backend-0.1.0-SNAPSHOT.jar
```

```bash
./mvnw -DskipTests package
java -jar target/wattup-backend-0.1.0-SNAPSHOT.jar
```

## 자주 막히는 지점

| 증상 | 확인할 곳 |
| --- | --- |
| `Failed to fetch ... apache-maven` | 첫 실행의 Maven 다운로드가 가능한지 확인하고 Wrapper 명령을 다시 실행합니다. |
| `Communications link failure` | MySQL 서비스가 실행 중인지, JDBC URL의 포트가 실제 TCP 포트와 같은지 확인합니다. |
| `Access denied for user 'wattup'` | 3단계의 접속 확인을 다시 실행하고 계정 이름, 비밀번호, 스키마 권한을 확인합니다. |
| Flyway 마이그레이션 오류 | 서버 로그의 마이그레이션 번호와 SQL 오류를 먼저 확인합니다. 체크섬 오류가 나면 해당 버전을 적용할 당시의 Git 파일과 비교합니다. |
| 8080 포트 사용 중 | `SERVER_PORT=8081`로 바꾸고 확인 URL도 8081을 사용합니다. |
| Swagger가 404 | 서버를 시작한 환경에 `WATTUP_OPENAPI_ENABLED=true`를 설정하고 다시 기동한 뒤 `/swagger-ui/index.html`을 엽니다. |
