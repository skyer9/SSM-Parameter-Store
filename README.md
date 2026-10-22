# SSM-Parameter-Store

이 글에서는 **AWS Systems Manager(SSM) Parameter Store**에 DB 접속정보를 저장하고, Spring Boot 앱이 시작할 때 이 값을 가져오도록 구성합니다.

완성되면 이렇게 동작합니다.

```text
브라우저 ──▶ Spring Boot (EC2:8080) ──▶ PostgreSQL (같은 EC2:5432)
                    │
                    └── 시작 시 SSM Parameter Store에서 DB URL / 계정 / 비밀번호 조회
                        (EC2에 연결된 IAM Role로 인증, Access Key 불필요)
```

화면에는 PostgreSQL에서 조회한 **현재 시간**이 표시됩니다.

OS 는 Amazon Linux 2023 를 이용합니다.

## PostgreSQL 설치 및 설정

```bash
sudo dnf update -y
sudo dnf install -y postgresql16 postgresql16-server
```

```bash
sudo postgresql-setup --initdb
sudo systemctl enable --now postgresql
sudo systemctl status postgresql
```

## DB와 사용자 생성

```bash
sudo -u postgres psql
```

```sql
CREATE USER timeapp WITH PASSWORD 'ChangeMe-Strong-Pass!';
CREATE DATABASE timedb OWNER timeapp;
\q
```

## 비밀번호 인증 허용

Amazon Linux의 기본 설정은 로컬 TCP 접속에 ident 인증을 사용합니다. JDBC는 TCP(localhost)로 접속하므로 비밀번호 인증(scram-sha-256)으로 바꿔야 합니다.

```bash
sudo vi /var/lib/pgsql/data/pg_hba.conf
```

아래 두 줄의 마지막 값을 scram-sha-256으로 수정합니다.

```bash
# IPv4 local connections:
host    all             all             127.0.0.1/32            scram-sha-256
# IPv6 local connections:
host    all             all             ::1/128                 scram-sha-256
```

```bash
sudo systemctl restart postgresql
```

## 접속 테스트

```bash
psql -h localhost -U timeapp -d timedb -c "SELECT now();"
```

## Parameter Store에 접속정보 저장

핵심 규칙: 파라미터 이름 = Spring 프로퍼티 이름

Spring Cloud AWS는 지정한 경로(prefix) 아래의 파라미터를 읽어서, prefix를 뺀 나머지 이름을 Spring 프로퍼티 키로 사용합니다.

| Parameter Store 이름 | Spring 프로퍼티 |
|---|---|
| `/timeapp/prod/spring.datasource.url` | `spring.datasource.url` |
| `/timeapp/prod/spring.datasource.username` | `spring.datasource.username` |
| `/timeapp/prod/spring.datasource.password` | `spring.datasource.password` |

AWS CLI 를 이용해 아래 명령을 수행합니다.
IAM 관리자 권한을 가지고 있어야 합니다.

관리자 권한이 설정되어 있는 로컬 PC에서 실행합니다.

```bash
aws ssm put-parameter --region ap-northeast-2 \
  --name "/timeapp/prod/spring.datasource.url" \
  --type String \
  --value "jdbc:postgresql://localhost:5432/timedb"

aws ssm put-parameter --region ap-northeast-2 \
  --name "/timeapp/prod/spring.datasource.username" \
  --type String \
  --value "timeapp"

aws ssm put-parameter --region ap-northeast-2 \
  --name "/timeapp/prod/spring.datasource.password" \
  --type SecureString \
  --value "ChangeMe-Strong-Pass!"
```

## IAM Role 생성 및 EC2에 연결

EC2에서 실행되는 앱이 Parameter Store를 읽으려면 권한이 필요합니다. Access Key를 서버에 두지 않고, EC2에 IAM Role을 붙이는 방식을 사용합니다.

### 정책 생성

IAM → 정책 → 정책 생성 → JSON 탭에 아래 내용을 입력합니다. <ACCOUNT_ID>는 본인 AWS 계정 ID로 바꿉니다.

```bash
# 관리자 권한이 설정되어 있는 로컬 PC에서 실행합니다.
aws sts get-caller-identity --query Account --output text
```

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadTimeAppParameters",
      "Effect": "Allow",
      "Action": [
        "ssm:GetParametersByPath",
        "ssm:GetParameters",
        "ssm:GetParameter"
      ],
      "Resource": [
        "arn:aws:ssm:ap-northeast-2:<ACCOUNT_ID>:parameter/timeapp/prod",
        "arn:aws:ssm:ap-northeast-2:<ACCOUNT_ID>:parameter/timeapp/prod/*"
      ]
    }
  ]
}
```

정책 이름은 timeapp-ssm-read로 지정합니다.

### Role 생성

IAM → 역할 → 역할 생성

- 신뢰할 수 있는 엔터티: AWS 서비스 → EC2
- 권한 정책: 방금 만든 timeapp-ssm-read 선택
- 역할 이름: timeapp-ec2-role

### EC2에 Role 연결

EC2 콘솔 → 인스턴스 선택 → 작업 → 보안 → IAM 역할 수정 → timeapp-ec2-role 선택 → 저장

### EC2에서 권한 확인

EC2에 SSH로 접속한 상태에서 실행합니다. Amazon Linux 2023에는 AWS CLI가 기본으로 설치되어 있습니다.

```bash
aws ssm get-parameters-by-path \
  --region ap-northeast-2 \
  --path /timeapp/prod/ \
  --with-decryption \
  --query "Parameters[].Name"
```

세 개의 파라미터 이름이 출력되면 권한 설정은 완료입니다.

## Spring Boot 앱 작성

start.spring.io에서 다음과 같이 생성합니다.

- Project: Gradle – Groovy
- Language: Java
- Spring Boot: 4.0.x (최신 패치 버전)
- Group: com.example / Artifact: timeapp
- Java: 21
- Dependencies: Spring Web, JDBC API, PostgreSQL Driver

프로젝트 구조

```text
timeapp
├── build.gradle
└── src/main
    ├── java/com/example/timeapp
    │   ├── TimeappApplication.java
    │   └── TimeController.java
    └── resources
        └── application.yml
```

### build.gradle

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.6'   // start.spring.io가 생성한 버전 사용
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        // Spring Boot 4.0.x에 맞는 Spring Cloud AWS 4.0.x
        mavenBom 'io.awspring.cloud:spring-cloud-aws-dependencies:4.0.2'
    }
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    runtimeOnly 'org.postgresql:postgresql'

    // SSM Parameter Store 연동
    implementation 'io.awspring.cloud:spring-cloud-aws-starter-parameter-store'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

### application.yml

- spring.config.import: 앱 시작 시 /timeapp/prod/ 아래의 모든 파라미터를 읽어 Spring 프로퍼티로 등록합니다.
- 파라미터를 읽지 못하면 앱이 시작되지 않습니다. 설정 누락을 배포 단계에서 바로 알 수 있으므로 의도된 동작입니다. 로컬 개발처럼 Parameter Store 없이 실행해야 할 때는 optional:aws-parameterstore:/timeapp/prod/처럼 optional:을 붙입니다.

```yaml
spring:
  application:
    name: timeapp
  config:
    import: "aws-parameterstore:/timeapp/prod/"
  cloud:
    aws:
      region:
        static: ap-northeast-2

server:
  port: 8080
```

### TimeController.java

```java
package com.example.timeapp;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TimeController {

    private final JdbcTemplate jdbcTemplate;

    public TimeController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String now() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT
                  to_char(now() AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS seoul_time,
                  to_char(now() AT TIME ZONE 'UTC',        'YYYY-MM-DD HH24:MI:SS') AS utc_time,
                  version()                                                         AS db_version
                """);

        return """
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="utf-8">
                  <title>DB 현재 시간</title>
                  <style>
                    body { font-family: sans-serif; max-width: 640px; margin: 60px auto; padding: 0 16px; }
                    .time { font-size: 2rem; font-weight: bold; margin: 8px 0 24px; }
                    .muted { color: #666; font-size: 0.9rem; }
                  </style>
                </head>
                <body>
                  <h1>PostgreSQL 현재 시간</h1>
                  <p>서울 (KST)</p>
                  <div class="time">%s</div>
                  <p>UTC</p>
                  <div class="time">%s</div>
                  <p class="muted">%s</p>
                  <p class="muted">접속정보 출처: AWS SSM Parameter Store</p>
                </body>
                </html>
                """.formatted(row.get("seoul_time"), row.get("utc_time"), row.get("db_version"));
    }
}
```

### 로컬에서 빌드

build/libs/timeapp-0.0.1-SNAPSHOT.jar 파일이 생성됩니다.

```bash
./gradlew clean bootJar
```

> 로컬 PC에는 IAM Role이 없으므로, 이 상태로 로컬에서 실행하면 Parameter Store를 읽지 못해 시작에 실패합니다. 로컬 실행용 설정은 10. 정리 및 다음 단계를 참고하세요.

### EC2에 Java 설치

```bash
sudo dnf install -y java-21-amazon-corretto-headless
java -version
```

### JAR 업로드

로컬 PC에서 실행합니다.

```bash
scp -i my-key.pem build/libs/timeapp-0.0.1-SNAPSHOT.jar ec2-user@<EC2_PUBLIC_IP>:/home/ec2-user/timeapp.jar
```

### 먼저 직접 실행해 보기

EC2 에서 실행합니다.

```bash
java -jar ~/timeapp.jar
```

로그에 Tomcat started on port 8080이 보이면 성공입니다. 확인 후 Ctrl + C로 종료합니다.

### systemd 서비스로 등록

SSH 접속을 끊어도 앱이 계속 실행되고, 서버가 재부팅되면 자동으로 시작되도록 등록합니다.

```bash
sudo mkdir -p /opt/timeapp
sudo mv ~/timeapp.jar /opt/timeapp/timeapp.jar
sudo chown -R ec2-user:ec2-user /opt/timeapp
```

```bash
sudo vi /etc/systemd/system/timeapp.service
------
[Unit]
Description=Time App (Spring Boot)
After=network-online.target postgresql.service
Wants=network-online.target
Requires=postgresql.service

[Service]
User=ec2-user
WorkingDirectory=/opt/timeapp
ExecStart=/usr/bin/java -jar /opt/timeapp/timeapp.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now timeapp
sudo systemctl status timeapp
```

로그 확인

```bash
journalctl -u timeapp -f
```

## 동작 확인

EC2 안에서 먼저 확인합니다.

```bash
curl http://localhost:8080/
```

그다음 브라우저에서 접속합니다.

```text
http://<EC2_PUBLIC_IP>:8080/
```

## 비밀번호 변경 테스트

PostgreSQL 비밀번호 변경

```bash
sudo -u postgres psql <<'EOF'
ALTER USER timeapp WITH PASSWORD 'New-Strong-Pass!';
EOF
```

관리자 권한이 있는 PC 에서 변경

```bash
aws ssm put-parameter --region ap-northeast-2 \
  --name "/timeapp/prod/spring.datasource.password" \
  --type SecureString \
  --value "New-Strong-Pass!" \
  --overwrite
```

EC2 에서 앱 재시작

```bash
sudo systemctl restart timeapp
```

## 파라미터 지우기 (테스트 종료)

delete-parameters는 한 번에 최대 10개까지만 지울 수 있습니다.

```bash
aws ssm get-parameters-by-path --region ap-northeast-2 \
  --path /timeapp/prod/ --recursive \
  --query "Parameters[].Name" --output text \
| xargs aws ssm delete-parameters --region ap-northeast-2 --names
```

## 정보를 읽어오는 과정

application.yml 을 확인합니다.

spring.config.import: aws-parameterstore:/timeapp/prod/ 를 확안하고 값을 가져오게 됩니다.

spring.datasource.url 등은 디폴트 스프링 부트 변수명이라 설정없이도 읽어오게 됩니다.

파라미터 스토어에도 동일한 변수명으로 저장했으므로 별도 설정없이 값을 가져오게 됩니다.
