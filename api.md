# VPC Peering 자동화 — API 명세서

Jumphost 이전 프로세스에서 사용되는 모든 REST API를 정리한 문서입니다.

---

## 공통 응답 형식

모든 API는 아래 구조로 응답합니다.

```json
{
  "success": true,
  "message": "SUCCESS",
  "data": { ... }
}
```

**오류 응답 예시 (400 / 404 / 500)**
```json
{
  "success": false,
  "message": "오류 메시지",
  "data": null
}
```

---

## account 파라미터 공통 설명

여러 AWS 조회 API에서 `account` 파라미터를 사용합니다.

| 값 | 의미 |
|----|------|
| `requester` (기본값) | 피어링 요청자 계정의 AWS 클라이언트를 사용 |
| `accepter` | 피어링 수락자 계정의 AWS 클라이언트를 사용 (Cross-Account AssumeRole) |

---

## 1. AWS 리소스 조회 API

기본 경로: `/api/v1/aws`

Jumphost 이전 마법사에서 VPC 정보를 입력할 때 자동으로 호출되어 라우팅 테이블, CIDR, VPC 이름 등을 채워 주는 API 그룹입니다.

---

### 1-1. 라우팅 테이블 목록 조회

> VPC ID를 입력하면 해당 VPC에 속한 라우팅 테이블 목록을 반환합니다.
> Main 라우팅 테이블이 항상 목록 맨 앞에 정렬됩니다.

```
GET /api/v1/aws/route-tables
```

**요청 파라미터**

| 파라미터 | 필수 | 설명 | 예시 |
|---------|------|------|------|
| `vpcId` | 필수 | 조회할 VPC ID | `vpc-0abc1234` |
| `account` | 선택 (기본값: `requester`) | 사용할 AWS 계정 | `requester` \| `accepter` |

**응답 데이터** `List<RouteTableDto>`

```json
[
  {
    "routeTableId": "rtb-0abc1234",
    "name": "main-route-table",
    "main": true,
    "subnetCount": 3
  },
  {
    "routeTableId": "rtb-0def5678",
    "name": "private-route-table",
    "main": false,
    "subnetCount": 1
  }
]
```

**사용 화면**: 피어링 생성 폼 / Jumphost 이전 Step 2 라우팅 테이블 드롭다운

---

### 1-2. VPC 내 EC2 + RDS 인스턴스 목록 조회

> VPC에 배포된 EC2 인스턴스와 RDS 인스턴스 목록을 반환합니다.
> RDS 조회 권한이 없을 경우 EC2 결과는 정상 반환되고 `rdsError` 필드에 오류 메시지가 담깁니다.

```
GET /api/v1/aws/vpc-resources
```

**요청 파라미터**

| 파라미터 | 필수 | 설명 | 예시 |
|---------|------|------|------|
| `vpcId` | 필수 | 조회할 VPC ID | `vpc-0abc1234` |
| `account` | 선택 (기본값: `requester`) | 사용할 AWS 계정 | `requester` \| `accepter` |

**응답 데이터** `VpcResourceResponse`

```json
{
  "ec2Instances": [
    { "instanceId": "i-0abc", "name": "jumphost-a", "instanceType": "t3.micro", "privateIp": "10.0.1.5", "state": "running" }
  ],
  "rdsInstances": [
    { "dbInstanceId": "db-0abc", "engine": "mysql", "endpoint": "db.xxxxx.rds.amazonaws.com", "status": "available" }
  ],
  "rdsError": null
}
```

**사용 화면**: 피어링 생성 폼 인스턴스 미리보기 / Jumphost 이전 Step 3 수락자 리소스 확인

---

### 1-3. VPC CIDR 조회

> VPC ID를 입력하면 해당 VPC의 기본 IPv4 CIDR 블록을 반환합니다.
> Jumphost 이전 Step 2에서 새 VPC ID 입력 후 CIDR 필드를 자동으로 채울 때 호출됩니다.

```
GET /api/v1/aws/vpc-cidr
```

**요청 파라미터**

| 파라미터 | 필수 | 설명 | 예시 |
|---------|------|------|------|
| `vpcId` | 필수 | 조회할 VPC ID | `vpc-0abc1234` |
| `account` | 선택 (기본값: `requester`) | 사용할 AWS 계정 | `requester` \| `accepter` |

**응답 데이터** `String`

```json
{
  "success": true,
  "message": "SUCCESS",
  "data": "10.3.0.0/16"
}
```

**사용 화면**: Jumphost 이전 Step 2 CIDR 자동 입력

---

### 1-4. VPC 이름 태그 조회

> VPC ID에 설정된 AWS Name 태그 값을 반환합니다.
> 태그가 없으면 빈 문자열을 반환하며 404를 내지 않습니다.
> 이전 이력 상세 팝업에서 `vpc-id(VPC이름)` 형식으로 표기하기 위해 사용됩니다.

```
GET /api/v1/aws/vpc-name
```

**요청 파라미터**

| 파라미터 | 필수 | 설명 | 예시 |
|---------|------|------|------|
| `vpcId` | 필수 | 조회할 VPC ID | `vpc-0abc1234` |
| `account` | 선택 (기본값: `requester`) | 사용할 AWS 계정 | `requester` \| `accepter` |

**응답 데이터** `String`

```json
{
  "success": true,
  "message": "SUCCESS",
  "data": "VPC-A"
}
```

**사용 화면**: Jumphost 이전 마법사 VPC 이름 표시 / 이전 이력 상세 팝업

---

### 1-5. VPC 보안 그룹 전체 조회

> VPC에 연결된 EC2 인스턴스의 보안 그룹 전체를 인바운드/아웃바운드 규칙 포함하여 반환합니다.
> 이전 이력 상세의 변경 내역 비교 팝업에서 `SECURITY_GROUP_POST` 스냅샷이 없을 때 실시간 폴백으로 호출됩니다.

```
GET /api/v1/aws/vpc-security-groups
```

**요청 파라미터**

| 파라미터 | 필수 | 설명 | 예시 |
|---------|------|------|------|
| `vpcId` | 필수 | 조회할 VPC ID | `vpc-0abc1234` |
| `account` | 선택 (기본값: `accepter`) | 사용할 AWS 계정 | `requester` \| `accepter` |

**응답 데이터** `List<SecurityGroupDetailDto>`

```json
[
  {
    "groupId": "sg-0abc1234",
    "groupName": "jumphost-sg",
    "description": "Jumphost security group",
    "inboundRules": [
      { "protocol": "tcp", "fromPort": 22, "toPort": 22, "source": "10.0.0.0/16", "description": "SSH from VPC-A" }
    ],
    "outboundRules": [
      { "protocol": "-1", "fromPort": 0, "toPort": 0, "source": "0.0.0.0/0", "description": "All traffic" }
    ]
  }
]
```

**사용 화면**: 이전 이력 상세 팝업 변경 내역 비교 (TO-BE 탭 실시간 조회)

---

### 1-6. 라우팅 테이블 상세 조회

> 라우팅 테이블 ID를 입력하면 해당 테이블의 모든 경로(Route) 항목을 반환합니다.
> 이전 이력 상세 팝업에서 현재(TO-BE) 상태를 과거(AS-IS) 스냅샷과 비교할 때 사용됩니다.

```
GET /api/v1/aws/route-table-detail
```

**요청 파라미터**

| 파라미터 | 필수 | 설명 | 예시 |
|---------|------|------|------|
| `rtbId` | 필수 | 조회할 라우팅 테이블 ID | `rtb-0abc1234` |
| `account` | 선택 (기본값: `requester`) | 사용할 AWS 계정 | `requester` \| `accepter` |

**응답 데이터** `RouteTableDetailDto`

```json
{
  "routeTableId": "rtb-0abc1234",
  "vpcId": "vpc-0abc1234",
  "main": true,
  "name": "main-route-table",
  "routes": [
    { "destinationCidr": "10.0.0.0/16", "target": "local", "state": "active", "vpcPeeringConnectionId": null },
    { "destinationCidr": "10.3.0.0/16", "target": "pcx-0abc1234", "state": "active", "vpcPeeringConnectionId": "pcx-0abc1234" }
  ],
  "subnetIds": ["subnet-0abc", "subnet-0def"]
}
```

**사용 화면**: 이전 이력 상세 팝업 변경 내역 비교 (라우팅 테이블 현재 상태)

---

### 1-7. 보안 그룹 상세 조회

> 쉼표로 구분된 보안 그룹 ID 목록을 입력하면 각 그룹의 인바운드/아웃바운드 규칙을 반환합니다.
> 피어링 생성 폼에서 인스턴스에 연결된 보안 그룹 규칙을 미리 보여 주기 위해 사용됩니다.

```
GET /api/v1/aws/security-groups
```

**요청 파라미터**

| 파라미터 | 필수 | 설명 | 예시 |
|---------|------|------|------|
| `sgIds` | 필수 | 쉼표로 구분된 보안 그룹 ID 목록 | `sg-0abc,sg-0def` |
| `account` | 선택 (기본값: `requester`) | 사용할 AWS 계정 | `requester` \| `accepter` |

**응답 데이터** `List<SecurityGroupDetailDto>` — 1-5와 동일한 구조

**사용 화면**: 피어링 생성 폼 보안 그룹 상세 모달

---

## 2. VPC Peering 관리 API

기본 경로: `/api/v1/peerings`

Jumphost 이전의 핵심 API입니다. 피어링 생성, 삭제, 이전 시작을 담당합니다.

---

### 2-1. 피어링 생성

> 5단계 AWS 작업을 순서대로 실행합니다.
> (1) 피어링 연결 요청 → (2) 수락 → (3) 요청자 라우팅 추가 → (4) 수락자 라우팅 추가 → (5) 수락자 보안 그룹 인바운드 규칙 추가
> 모든 단계 완료 후 DB에 ACTIVE 상태로 이력을 저장합니다.

```
POST /api/v1/peerings
```

**요청 Body**

```json
{
  "peeringName": "VPC-A-to-Jumphost-B",
  "requesterVpcId": "vpc-0aaa1111",
  "requesterCidr": "10.0.0.0/16",
  "requesterRouteTableId": "rtb-0aaa1111",
  "accepterVpcId": "vpc-0bbb2222",
  "accepterCidr": "10.3.0.0/16",
  "accepterRouteTableId": "rtb-0bbb2222",
  "accepterSecurityGroupId": "sg-0bbb2222"
}
```

**응답 데이터** `PeeringHistoryResponse` — HTTP 201 Created

```json
{
  "id": 1,
  "peeringConnectionId": "pcx-0abc1234",
  "peeringName": "VPC-A-to-Jumphost-B",
  "status": "ACTIVE",
  "requesterVpcId": "vpc-0aaa1111",
  "requesterCidr": "10.0.0.0/16",
  "accepterVpcId": "vpc-0bbb2222",
  "accepterCidr": "10.3.0.0/16",
  "createdAt": "2025-01-01T10:00:00"
}
```

**사용 화면**: 피어링 생성 폼 제출

---

### 2-2. 활성 피어링 목록 조회

> 현재 ACTIVE 상태인 피어링 목록을 페이지 단위로 반환합니다.
> Jumphost 이전 Step 1에서 이전 대상 피어링을 선택할 때도 이 API를 사용합니다.

```
GET /api/v1/peerings
```

**요청 파라미터**

| 파라미터 | 필수 | 설명 | 기본값 |
|---------|------|------|--------|
| `page` | 선택 | 페이지 번호 (0부터 시작) | `0` |
| `size` | 선택 | 페이지 크기 | `20` |

**응답 데이터** `PageResult<PeeringHistoryResponse>`

**사용 화면**: 피어링 목록 탭 / Jumphost 이전 Step 1 이전 대상 드롭다운

---

### 2-3. 전체 피어링 이력 조회

> 삭제된 피어링 포함 전체 이력을 최신순으로 반환합니다.

```
GET /api/v1/peerings/history
```

**요청 파라미터** — 2-2와 동일

**사용 화면**: 피어링 이력 탭

---

### 2-4. 피어링 단건 조회

> 피어링 이력 ID로 단건을 조회합니다. 존재하지 않으면 404를 반환합니다.

```
GET /api/v1/peerings/{id}
```

**경로 변수**

| 변수 | 설명 |
|------|------|
| `id` | 피어링 이력 PK |

**응답 데이터** `PeeringHistoryResponse`

---

### 2-5. 피어링 스냅샷 조회

> 피어링 생성/삭제 시점에 저장된 네트워크 스냅샷(라우팅 테이블, 보안 그룹 JSON)을 반환합니다.
> 이전 이력 상세 팝업의 변경 내역 비교(AS-IS / TO-BE)에서 핵심적으로 사용됩니다.

```
GET /api/v1/peerings/{id}/snapshots
```

**경로 변수**

| 변수 | 설명 |
|------|------|
| `id` | 피어링 이력 PK |

**응답 데이터** `List<NetworkSnapshotResponse>`

```json
[
  {
    "dataType": "ROUTE_TABLE",
    "snapshotData": "{ ... 라우팅 테이블 JSON ... }",
    "createdAt": "2025-01-01T10:00:00"
  },
  {
    "dataType": "SECURITY_GROUP",
    "snapshotData": "{ ... 보안 그룹 JSON ... }",
    "createdAt": "2025-01-01T10:00:00"
  },
  {
    "dataType": "SECURITY_GROUP_POST",
    "snapshotData": "{ ... 피어링 생성 후 보안 그룹 JSON ... }",
    "createdAt": "2025-01-01T10:00:05"
  }
]
```

**dataType 설명**

| 값 | 저장 시점 | 용도 |
|----|----------|------|
| `ROUTE_TABLE` | 피어링 생성/삭제 직전 | AS-IS 라우팅 테이블 상태 |
| `SECURITY_GROUP` | 피어링 생성/삭제 직전 | AS-IS 보안 그룹 상태 |
| `SECURITY_GROUP_POST` | 피어링 생성 완료 직후 | TO-BE 보안 그룹 상태 비교 기준 |

**사용 화면**: 이전 이력 상세 팝업 변경 내역 비교

---

### 2-6. Jumphost 이전 시작 (1단계 — 신규 피어링 생성)

> Jumphost 이전의 첫 번째 단계입니다.
> 신규 Jumphost VPC와의 피어링을 새로 생성하되 기존 피어링(VPC-A ↔ 구 Jumphost)은 유지합니다.
> 이 시점부터 이전 이력(JumphostMigrationHistory)이 IN_PROGRESS 상태로 DB에 저장됩니다.
> 이후 완료 API 또는 롤백 API를 호출하여 이전을 마무리합니다.

```
POST /api/v1/peerings/{id}/migrate/start
```

**경로 변수**

| 변수 | 설명 |
|------|------|
| `id` | 교체 대상 기존 피어링 이력 PK |

**요청 Body**

```json
{
  "newRequesterVpcId": "vpc-0ccc3333",
  "newRequesterCidr": "10.5.0.0/16",
  "newRequesterRouteTableId": "rtb-0ccc3333",
  "newRequesterAccountId": "123456789012"
}
```

**응답 데이터** `PeeringMigrationStartResponse` — HTTP 201 Created

```json
{
  "migrationId": 10,
  "newPeering": { ... PeeringHistoryResponse ... },
  "existingPeeringId": 1
}
```

**사용 화면**: Jumphost 이전 마법사 Step 3 "신규 피어링 생성 시작" 버튼

---

### 2-7. Jumphost 이전 (단일 단계 — 신규 생성 + 기존 삭제 동시)

> 신규 피어링 생성과 기존 피어링 삭제를 한 번에 처리합니다.
> 2-6(migrate/start) + 3-1(complete)를 단계별 확인 없이 자동으로 실행합니다.

```
POST /api/v1/peerings/{id}/migrate
```

**경로 변수**

| 변수 | 설명 |
|------|------|
| `id` | 교체 대상 기존 피어링 이력 PK |

**요청 Body** — 2-6과 동일

**응답 데이터** `PeeringMigrationResponse`

```json
{
  "newPeering": { ... PeeringHistoryResponse ... },
  "deletedPeeringId": 1
}
```

---

### 2-8. 피어링 삭제

> AWS 리소스를 역순으로 삭제합니다.
> (1) 수락자 보안 그룹 인바운드 규칙 제거 → (2) 수락자 라우팅 제거 → (3) 요청자 라우팅 제거 → (4) 피어링 연결 삭제
> 삭제 직전 스냅샷을 저장하고 DB 이력 상태를 DELETED로 변경합니다.

```
DELETE /api/v1/peerings/{id}
```

**경로 변수**

| 변수 | 설명 |
|------|------|
| `id` | 삭제할 피어링 이력 PK |

**응답** HTTP 204 No Content (응답 body 없음)

**사용 화면**: 피어링 목록 탭 삭제 버튼

---

## 3. Jumphost 이전 이력 API

기본 경로: `/api/v1/migrations`

이전 마법사에서 Step 3 완료 후 생성된 이전 이력을 관리하는 API입니다.

---

### 3-1. 이전 완료

> IN_PROGRESS 상태의 이전 이력에서 호출합니다.
> 기존 피어링(VPC-A ↔ 구 Jumphost)을 삭제하고 이전 이력을 COMPLETED 상태로 변경합니다.
> 이 시점부터 트래픽은 신규 Jumphost를 통해서만 흐릅니다.

```
POST /api/v1/migrations/{id}/complete
```

**경로 변수**

| 변수 | 설명 |
|------|------|
| `id` | 이전 이력 PK |

**응답 데이터** `MigrationHistoryResponse`

```json
{
  "id": 10,
  "oldPeering": { ... 삭제된 기존 피어링 ... },
  "newPeering": { ... 유지되는 신규 피어링 ... },
  "status": "COMPLETED",
  "createdAt": "2025-01-01T10:00:00"
}
```

**사용 화면**: Jumphost 이전 마법사 Step 4 완료 버튼 / 이전 이력 목록 완료 버튼

---

### 3-2. 이전 롤백

> 이전 이력 상태에 따라 다르게 동작합니다.
>
> - **IN_PROGRESS** 상태: 신규 피어링만 삭제하여 이전 전 상태로 복원합니다.
> - **COMPLETED** 상태: 신규 피어링 삭제 + 기존 피어링(VPC-A ↔ 구 Jumphost) 재생성으로 완전 복원합니다.

```
POST /api/v1/migrations/{id}/rollback
```

**경로 변수**

| 변수 | 설명 |
|------|------|
| `id` | 이전 이력 PK |

**응답 데이터** `MigrationHistoryResponse`

```json
{
  "id": 10,
  "status": "ROLLED_BACK",
  ...
}
```

**사용 화면**: Jumphost 이전 마법사 Step 4 롤백 버튼 / 이전 이력 목록 롤백 버튼

---

### 3-3. 이전 이력 목록 조회

> 전체 Jumphost 이전 이력을 최신순으로 반환합니다.

```
GET /api/v1/migrations
```

**요청 파라미터**

| 파라미터 | 필수 | 설명 | 기본값 |
|---------|------|------|--------|
| `page` | 선택 | 페이지 번호 (0부터 시작) | `0` |
| `size` | 선택 | 페이지 크기 | `20` |

**응답 데이터** `PageResult<MigrationHistoryResponse>`

**사용 화면**: Jumphost 이전 이력 탭

---

### 3-4. 이전 이력 단건 조회

> 이전 이력 ID로 단건을 조회합니다. 존재하지 않으면 404를 반환합니다.

```
GET /api/v1/migrations/{id}
```

**경로 변수**

| 변수 | 설명 |
|------|------|
| `id` | 이전 이력 PK |

**응답 데이터** `MigrationHistoryResponse`

---

## 4. Jumphost 이전 전체 흐름 — API 호출 순서

아래는 Jumphost 이전 마법사에서 각 단계별로 실제 호출되는 API를 정리한 흐름도입니다.

```
[Step 1 — 이전 대상 선택]
  GET /api/v1/peerings?size=100
      └─ 활성 피어링 목록 조회 (드롭다운 채우기)

  GET /api/v1/aws/vpc-name?vpcId={requesterVpcId}&account=requester  (병렬)
  GET /api/v1/aws/vpc-name?vpcId={accepterVpcId}&account=accepter    (병렬)
      └─ 선택한 피어링의 VPC 이름 표시

[Step 2 — 신규 Jumphost VPC 입력]
  (조회 버튼 클릭 시)
  GET /api/v1/aws/vpc-cidr?vpcId={newVpcId}&account=requester
      └─ CIDR 자동 입력

  GET /api/v1/aws/route-tables?vpcId={newVpcId}&account=requester
      └─ 라우팅 테이블 드롭다운 채우기

  GET /api/v1/aws/vpc-name?vpcId={newVpcId}&account=requester
      └─ VPC 이름 표시

  (유효성 검사 — 동일 VPC ID 또는 CIDR 겹침 경고 표시)

[Step 3 — 미리보기 확인 및 이전 시작]
  GET /api/v1/aws/vpc-resources?vpcId={accepterVpcId}&account=accepter  (병렬)
  GET /api/v1/aws/route-tables?vpcId={accepterVpcId}&account=accepter    (병렬)
  GET /api/v1/aws/security-groups?sgIds={sgId}&account=accepter          (병렬)
      └─ 수락자 VPC 리소스 미리보기

  (이전 시작 버튼 클릭 시)
  POST /api/v1/peerings/{id}/migrate/start
      └─ 신규 피어링 생성, 이전 이력 IN_PROGRESS로 저장

[Step 4 — 완료 또는 롤백]
  POST /api/v1/migrations/{migrationId}/complete
      └─ 기존 피어링 삭제, 이전 이력 COMPLETED 처리

  POST /api/v1/migrations/{migrationId}/rollback
      └─ 신규 피어링 삭제 (IN_PROGRESS) 또는 완전 복원 (COMPLETED)

[이전 이력 상세 팝업]
  GET /api/v1/peerings/{newPeeringId}/snapshots                          (병렬)
  GET /api/v1/aws/route-table-detail?rtbId={rtbId}&account=accepter      (병렬)
  GET /api/v1/aws/vpc-security-groups?vpcId={vpcId}&account=accepter     (병렬)
  GET /api/v1/aws/vpc-name?vpcId={oldReqVpcId}&account=requester         (병렬)
  GET /api/v1/aws/vpc-name?vpcId={newReqVpcId}&account=requester         (병렬)
  GET /api/v1/aws/vpc-name?vpcId={accepterVpcId}&account=accepter        (병렬)
      └─ AS-IS / TO-BE 변경 내역 비교 렌더링
```

---

## 5. 이전 이력 상태값

| 상태 | 의미 | 가능한 다음 상태 |
|------|------|----------------|
| `IN_PROGRESS` | 신규 피어링 생성 완료, 기존 피어링 유지 중 | `COMPLETED` 또는 `ROLLED_BACK` |
| `COMPLETED` | 기존 피어링 삭제 완료, 이전 완료 | `ROLLED_BACK` (완전 복원 가능) |
| `ROLLED_BACK` | 이전 취소, 원래 상태로 복원됨 | 없음 (최종 상태) |
