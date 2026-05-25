# VPC Peering 자동화 콘솔

AWS VPC Peering 생성·삭제·마이그레이션을 자동화하는 Spring Boot 기반 웹 애플리케이션입니다.
단순 피어링 관리에 더해, Jumphost 교체 시 기존 연결을 유지하면서 신규 피어링을 단계적으로 전환하는
2단계 이전(Migration) 기능을 제공합니다.

---

## 목차

1. [기술 스택](#기술-스택)
2. [프로젝트 구조](#프로젝트-구조)
3. [주요 기능](#주요-기능)
4. [데이터베이스 스키마](#데이터베이스-스키마)
5. [API 엔드포인트](#api-엔드포인트)
6. [환경 변수 설정](#환경-변수-설정)
7. [로컬 실행 방법](#로컬-실행-방법)
8. [AWS 권한 요구사항](#aws-권한-요구사항)

---

## 기술 스택

| 항목 | 버전 |
|------|------|
| Java | 11 |
| Spring Boot | 2.7.18 |
| MyBatis | 2.3.1 |
| MariaDB | 10.3 이상 |
| Flyway | Spring Boot 관리 |
| AWS SDK v2 | 2.25.11 |
| Thymeleaf | Spring Boot 관리 |
| Bootstrap | 5.3.3 (CDN) |
| 빌드 도구 | Maven 4.0.0 |

---

## 프로젝트 구조

```
src/main/
├── java/com/company/vpc/
│   ├── config/              # AWS 클라이언트, Flyway 설정
│   ├── controller/          # REST 컨트롤러 (VPC, Migration, AWS Query, View)
│   ├── domain/              # 엔티티 클래스 (PeeringHistory, NetworkSnapshot 등)
│   ├── dto/
│   │   ├── request/         # 요청 DTO
│   │   └── response/        # 응답 DTO
│   ├── exception/           # BusinessException, ErrorCode, GlobalExceptionHandler
│   ├── mapper/              # MyBatis Mapper 인터페이스
│   ├── service/
│   │   ├── aws/             # AWS SDK 연동 (AwsPeeringServiceImpl, AwsResourceQueryService)
│   │   └── PeeringManagementService.java  # 비즈니스 오케스트레이션
│   ├── common/              # ApiResponse, BaseTimeEntity, PageResult
│   └── util/                # CidrUtils
├── resources/
│   ├── db/migration/        # Flyway SQL 마이그레이션 (V1, V2)
│   ├── mapper/              # MyBatis XML Mapper
│   ├── static/
│   │   ├── css/app.css      # 커스텀 스타일
│   │   └── js/              # 기능별 분리된 JS 파일
│   ├── templates/index.html # Thymeleaf 메인 페이지 1 (SPA)
│   ├── application.yml
│   └── application-local.yml
```

### 프론트엔드 JS 파일 구성

| 파일 | 역할 |
|------|------|
| `utils.js` | 공통 유틸리티 (apiFetch, showToast, escHtml 등) 및 API 경로 상수 |
| `cidr.js` | CIDR 형식 검증 및 대역 중복 계산 |
| `diff-render.js` | AS-IS / TO-BE 라우팅 테이블·보안 그룹 변경 사항 렌더링 |
| `peering-create.js` | 피어링 생성 폼, VPC 리소스 조회, 보안 그룹 상세 모달 |
| `peering-list.js` | 활성 목록·전체 이력 조회, 삭제 처리 |
| `migration-wizard.js` | Jumphost 이전 마법사 (4단계 상태 머신) |
| `migration-history.js` | 이전 이력 목록, 상세 모달, 변경 내역 비교, 완료·롤백 처리 |

---

## 주요 기능

### 1. VPC Peering 생성

- 요청자(Requester) VPC와 수락자(Accepter) VPC 간 피어링 연결 생성
- 연결 수락, 양방향 라우팅 테이블 경로 추가, 보안 그룹 ICMP 인바운드 규칙 설정을 순서대로 자동 수행
- 크로스 계정(Cross-Account) 피어링 지원 (STS AssumeRole 사용)
- CIDR 대역 중복 여부 사전 검증

### 2. VPC Peering 삭제

- 피어링 삭제 전 라우팅 테이블·보안 그룹 상태를 JSON 스냅샷으로 저장
- 삭제 실패 시 보상 트랜잭션으로 이전 상태 복구 시도

### 3. Jumphost 이전 마법사 (2단계 이전)

기존 Jumphost(VPC-A)를 신규 Jumphost(VPC-C)로 교체할 때 서비스 중단 없이 전환합니다.

```
1단계: 신규 피어링 생성 (VPC-C <-> VPC-B)  - 기존 피어링(VPC-A <-> VPC-B) 유지
2단계: 연결 검증 후 결정
  - 완료: 기존 피어링 삭제
  - 롤백: 신규 피어링 삭제 후 원상복구
```

- 이전 시작 전 수락자 VPC(VPC-B)의 라우팅 테이블·보안 그룹 스냅샷 자동 저장
- 이전 이력에서 진행중 항목에 대해 완료 또는 롤백 처리 가능 333

### 4. 이전 이력 관리

- Jumphost 이전 이력 목록 (페이지네이션)
- 이전 상세 팝업: AS-IS(이전 전 스냅샷) / TO-BE(현재 실시간) 비교
  - 신규 추가 항목: 녹색 하이라이트
  - 완료 시 삭제될 항목: 분홍색 하이라이트
  - 요약 뷰 / JSON 뷰 전환
- 변경 내역 비교 팝업 (라우팅 테이블·보안 그룹 diff)

### 5. AWS 리소스 조회

- VPC ID 기준 라우팅 테이블 목록 조회 (Main 우선 정렬)
- VPC 내 EC2·RDS 인스턴스 목록 조회
- 보안 그룹 상세 조회 (인바운드·아웃바운드 규칙)
- VPC CIDR 및 Name 태그 자동 조회

---

## 데이터베이스 스키마

### peering_history

VPC Peering 연결의 전체 생명주기를 기록합니다.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT | PK, 자동 증가 |
| peering_connection_id | VARCHAR(50) | AWS 피어링 ID (pcx-xxx) |
| peering_name | VARCHAR(100) | 피어링 이름 |
| csp_type | VARCHAR(20) | 클라우드 제공자 (AWS) |
| requester_vpc_id | VARCHAR(50) | 요청자 VPC ID |
| requester_cidr | VARCHAR(20) | 요청자 CIDR |
| requester_account_id | VARCHAR(20) | 요청자 AWS 계정 ID |
| requester_route_table_id | VARCHAR(50) | 요청자 라우팅 테이블 ID |
| accepter_vpc_id | VARCHAR(50) | 수락자 VPC ID |
| accepter_cidr | VARCHAR(20) | 수락자 CIDR |
| accepter_account_id | VARCHAR(20) | 수락자 AWS 계정 ID |
| accepter_route_table_id | VARCHAR(50) | 수락자 라우팅 테이블 ID |
| accepter_security_group_id | VARCHAR(50) | 수락자 보안 그룹 ID |
| status | VARCHAR(20) | PENDING / ACTIVE / DELETED / FAILED |
| created_at | DATETIME | 생성 시각 |
| updated_at | DATETIME | 수정 시각 |
| deleted_at | DATETIME | 삭제 시각 (논리 삭제) |

### network_snapshot

피어링 생성·삭제·이전 시점의 네트워크 상태를 JSON으로 저장합니다.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT | PK |
| peering_history_id | BIGINT | FK (peering_history.id) |
| data_type | VARCHAR(30) | ROUTE_TABLE / SECURITY_GROUP / SECURITY_GROUP_POST |
| snapshot_data | LONGTEXT | JSON 스냅샷 데이터 |
| created_at | DATETIME | 생성 시각 |
| updated_at | DATETIME | 수정 시각 |

### jumphost_migration_history

Jumphost 이전 이력을 관리합니다.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT | PK |
| old_peering_id | BIGINT | FK — 기존 피어링 (VPC-A) |
| new_peering_id | BIGINT | FK — 신규 피어링 (VPC-C) |
| status | VARCHAR(20) | IN_PROGRESS / COMPLETED / ROLLED_BACK |
| completed_at | DATETIME | 완료 시각 |
| created_at | DATETIME | 생성 시각 |
| updated_at | DATETIME | 수정 시각 |

---

## API 엔드포인트

모든 응답은 `ApiResponse<T>` 공통 래퍼를 사용합니다.

```json
{
  "success": true,
  "message": "SUCCESS",
  "data": { ... }
}
```

### VPC Peering (/api/v1/peerings)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | /api/v1/peerings | 피어링 생성 |
| GET | /api/v1/peerings | 활성 피어링 목록 조회 (페이징) |
| GET | /api/v1/peerings/history | 전체 이력 조회 |
| GET | /api/v1/peerings/{id} | 피어링 단건 조회 |
| GET | /api/v1/peerings/{id}/snapshots | 네트워크 스냅샷 조회 |
| POST | /api/v1/peerings/{id}/migrate/start | Jumphost 이전 시작 (2단계) |
| POST | /api/v1/peerings/{id}/migrate | Jumphost 이전 단일 실행 |
| DELETE | /api/v1/peerings/{id} | 피어링 삭제 |

### Jumphost 이전 (/api/v1/migrations)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | /api/v1/migrations/{id}/complete | 이전 완료 (기존 피어링 삭제) |
| POST | /api/v1/migrations/{id}/rollback | 이전 롤백 (신규 피어링 삭제) |
| GET | /api/v1/migrations | 이전 이력 목록 조회 (페이징) |
| GET | /api/v1/migrations/{id} | 이전 이력 단건 조회 |

### AWS 리소스 조회 (/api/v1/aws)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | /api/v1/aws/route-tables | VPC 기준 라우팅 테이블 목록 |
| GET | /api/v1/aws/vpc-resources | VPC 내 EC2·RDS 인스턴스 목록 |
| GET | /api/v1/aws/vpc-cidr | VPC 기본 IPv4 CIDR 조회 |
| GET | /api/v1/aws/vpc-name | VPC Name 태그 값 조회 |
| GET | /api/v1/aws/vpc-security-groups | VPC 내 보안 그룹 전체 상세 조회 |
| GET | /api/v1/aws/route-table-detail | 라우팅 테이블 상세 (경로 목록 포함) |
| GET | /api/v1/aws/security-groups | 보안 그룹 상세 (복수 ID 조회) |

---

## 환경 변수 설정

`application-local.yml.example`을 복사하여 `application-local.yml`을 생성하고 아래 환경 변수를 설정합니다.

### 데이터베이스

| 환경 변수 | 설명 | 예시 |
|-----------|------|------|
| DB_URL | MariaDB JDBC URL | jdbc:mariadb://localhost:3306/vpc_db |
| DB_USERNAME | 데이터베이스 사용자명 | vpc_user |
| DB_PASSWORD | 데이터베이스 비밀번호 | - |

### AWS — 요청자(Requester) 계정

| 환경 변수 | 설명 | 기본값 |
|-----------|------|--------|
| AWS_REQUESTER_ACCESS_KEY | IAM 액세스 키 | - |
| AWS_REQUESTER_SECRET_KEY | IAM 시크릿 키 | - |
| AWS_REQUESTER_REGION | AWS 리전 | ap-northeast-2 |

### AWS — 수락자(Accepter) 계정

수락자 계정은 STS AssumeRole을 통해 임시 자격증명을 발급받아 접근합니다.

| 환경 변수 | 설명 | 기본값 |
|-----------|------|--------|
| AWS_ACCEPTER_ROLE_ARN | AssumeRole ARN | - |
| AWS_ACCEPTER_REGION | AWS 리전 | ap-northeast-2 |

---

## 로컬 실행 방법

### 사전 요구사항

- JDK 11 이상
- Maven 3.6 이상
- MariaDB 10.3 이상
- AWS 계정 및 IAM 자격증명

### 1. 데이터베이스 준비

```sql
CREATE DATABASE vpc_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'vpc_user'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON vpc_db.* TO 'vpc_user'@'localhost';
FLUSH PRIVILEGES;
```

### 2. 환경 설정 파일 생성

```bash
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml
```

`application-local.yml`에 실제 값을 입력합니다.

### 3. 빌드 및 실행

```bash
# 의존성 설치 및 빌드
mvn clean package -DskipTests

# 로컬 프로파일로 실행
java -jar target/vpc-*.jar --spring.profiles.active=local
```

또는 Maven 플러그인으로 직접 실행합니다.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 4. 접속

브라우저에서 `http://localhost:8080` 으로 접속합니다.

---

## AWS 권한 요구사항

### 요청자 계정 IAM 정책

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ec2:CreateVpcPeeringConnection",
        "ec2:AcceptVpcPeeringConnection",
        "ec2:DeleteVpcPeeringConnection",
        "ec2:DescribeVpcPeeringConnections",
        "ec2:CreateRoute",
        "ec2:DeleteRoute",
        "ec2:DescribeRouteTables",
        "ec2:DescribeVpcs",
        "ec2:DescribeInstances",
        "ec2:DescribeSecurityGroups",
        "ec2:AuthorizeSecurityGroupIngress",
        "ec2:RevokeSecurityGroupIngress",
        "rds:DescribeDBInstances",
        "sts:AssumeRole"
      ],
      "Resource": "*"
    }
  ]
}
```

### 수락자 계정 IAM 역할 (AssumeRole)

수락자 계정에 아래 신뢰 정책이 포함된 IAM 역할을 생성하고, 요청자 계정의 IAM 사용자가 AssumeRole 할 수 있도록 설정합니다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::{requester_account_id}:root"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

역할에 부여할 권한은 요청자 계정 정책에서 `sts:AssumeRole`을 제외한 항목과 동일합니다.

---

## 공통 응답 형식

```json
// 성공
{
  "success": true,
  "message": "SUCCESS",
  "data": { ... }
}

// 실패
{
  "success": false,
  "message": "오류 메시지",
  "data": null
}
```

HTTP 상태 코드:

| 상황 | 코드 |
|------|------|
| 정상 조회 | 200 OK |
| 정상 생성 | 201 Created |
| 정상 삭제 | 204 No Content |
| 비즈니스 오류 (중복, 유효성 실패 등) | 400 Bad Request |
| 리소스 없음 | 404 Not Found |
| 서버 오류 | 500 Internal Server Error |
