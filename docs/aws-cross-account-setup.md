# AWS 크로스 계정 VPC Peering — 자격증명 설정 가이드

## 1. 두 가지 방식 비교

### 방식 A: Static Credentials (이전 방식)
각 계정마다 IAM 사용자의 Access Key / Secret Key를 발급해서 직접 보관.

```
requester 계정 ──── access-key-A / secret-key-A
accepter  계정 ──── access-key-B / secret-key-B  ← 계정마다 키 쌍 추가
```

| 단점 |
|------|
| 장기 자격증명(무기한 유효) → 탈취 시 피해 큼 |
| 계정이 늘수록 관리할 키 쌍도 증가 |
| 키 교체(Rotation) 작업 번거로움 |

---

### 방식 B: AssumeRole (현재 적용 방식) ✅
requester 계정의 키 **하나만** 유지하고, accepter 계정에는 IAM Role을 생성해
requester가 그 역할을 **임시로 위임받아** 사용.

```
requester 계정 ──── access-key-A / secret-key-A  (이것 하나만 유지)
                         │
                         │  STS AssumeRole 호출
                         ▼
accepter  계정 ──── IAM Role (VpcPeeringRole)
                    임시 자격증명 발급 (1시간 후 자동 만료 & 자동 갱신)
```

| 장점 |
|------|
| 장기 자격증명 노출 계정 최소화 → 보안 강화 |
| 임시 자격증명(1시간 만료) → 탈취되어도 피해 제한적 |
| 계정 추가 시 코드 변경 없이 Role ARN만 추가 |
| AWS 권장 크로스 계정 접근 방식 |

---

## 2. AWS 콘솔 설정 (최초 1회)

### 2-1. accepter 계정에서 IAM Role 생성

1. **AWS 콘솔** → accepter 계정 로그인
2. **IAM** → **역할(Roles)** → **역할 만들기**
3. **신뢰할 수 있는 엔터티 유형**: `AWS 계정`
4. **Account ID**: requester 계정 ID 입력 (예: `111122223333`)
5. **권한 추가** (필요한 권한만 부여):

   ```
   AmazonEC2FullAccess          ← VPC Peering, 라우팅, SG 설정
   AmazonRDSReadOnlyAccess      ← RDS 인스턴스 조회
   ```

6. **역할 이름**: `VpcPeeringRole` (원하는 이름)
7. 생성 완료 후 **역할 ARN** 복사:
   ```
   arn:aws:iam::999988887777:role/VpcPeeringRole
   ```
   (999988887777 = accepter 계정 ID)

### 2-2. requester 계정 IAM 사용자에 STS 권한 추가

requester 계정의 IAM 사용자(access-key-A 소유자)에게 아래 권한 추가:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "arn:aws:iam::999988887777:role/VpcPeeringRole"
    }
  ]
}
```

---

## 3. application.yml 설정

```yaml
aws:
  requester:
    access-key: ${AWS_REQUESTER_ACCESS_KEY}   # requester 계정 IAM 사용자 키
    secret-key: ${AWS_REQUESTER_SECRET_KEY}
    region: ap-northeast-2

  accounts:                                   # accepter 계정 목록 (Map — 계정 추가 시 여기에만 추가)
    accepter:                                 # account 파라미터 이름 (자유롭게 지정)
      role-arn: arn:aws:iam::999988887777:role/VpcPeeringRole
      region: ap-northeast-2
      # access-key / secret-key 불필요 — AssumeRole로 임시 자격증명 자동 발급
```

> **계정 추가 방법**: `accounts` 맵에 항목만 추가하면 코드 변경 없이 즉시 사용 가능.

---

## 4. 자격증명 흐름 (코드 내부 동작)

```
1. 애플리케이션 시작
   └─ requester access-key/secret-key로 STS 클라이언트 초기화

2. accepter EC2/RDS 클라이언트 생성 요청
   └─ StsAssumeRoleCredentialsProvider가 STS에 AssumeRole 호출
        → AWS STS: 임시 AccessKeyId + SecretAccessKey + SessionToken 발급 (TTL 1시간)
        → 발급된 임시 자격증명으로 accepter 계정 클라이언트 초기화

3. 임시 자격증명 만료 전 자동 갱신
   └─ StsAssumeRoleCredentialsProvider가 만료 직전 자동으로 재발급
        → 애플리케이션 재시작 없이 지속 운영 가능
```

---

## 5. 계정이 3개 이상으로 늘어날 때

`accounts` 맵 구조이므로 항목을 추가하기만 하면 된다. **코드 변경 불필요.**

```yaml
aws:
  requester:
    access-key: ${AWS_REQUESTER_ACCESS_KEY}
    secret-key: ${AWS_REQUESTER_SECRET_KEY}
    region: ap-northeast-2

  accounts:
    accepter:                          # 기존 계정
      role-arn: arn:aws:iam::999988887777:role/VpcPeeringRole
      region: ap-northeast-2
    accepter-dev:                      # 추가 계정 — 이 블록만 추가하면 됨
      role-arn: arn:aws:iam::111111111111:role/VpcPeeringRole
      region: ap-southeast-1
    accepter-prod:                     # 또 다른 계정
      role-arn: arn:aws:iam::222222222222:role/VpcPeeringRole
      region: ap-northeast-2
```

추가한 계정은 API 호출 시 `account` 파라미터에 맵 키 이름을 그대로 사용한다.
예) `GET /api/v1/aws/vpcs/resources?vpcId=vpc-xxx&account=accepter-dev`

---

## 6. 보안 체크리스트

- [ ] requester access-key/secret-key는 환경변수 또는 AWS Secrets Manager로 관리
- [ ] accepter IAM Role의 신뢰 정책에 requester 계정 ID만 허용 (최소 권한)
- [ ] IAM Role 권한은 필요한 API만 허용 (EC2, RDS 최소 권한)
- [ ] CloudTrail로 AssumeRole 호출 이력 모니터링
- [ ] 운영 환경에서는 EC2 Instance Profile / ECS Task Role로 requester 키도 제거 권장
