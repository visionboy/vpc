# 프로젝트 가이드: 멀티 CSP 확장성을 고려한 VPC Peering 자동화 시스템 개발

## 1. 프로젝트 개요
- **목적**: 단일 AWS 계정 내 독립된 두 IAM 서브 계정(또는 리소스 영역)의 VPC 간 VPC Peering 생성/삭제를 자동화하고, MariaDB에 이력 및 스냅샷을 관리하는 API 백엔드와 웹 콘솔 화면을 개발한다.
- **기술 스택**: Java 11, Spring Boot 2.7.x (또는 3.x), Spring Data JPA, AWS SDK for Java v2 (`software.amazon.awssdk`), MariaDB, Vue 3 (또는 Thymeleaf/Bootstrap - 화면 구현용)

---

## 2. 핵심 설계 아키텍처: 멀티 CSP 확장성 (가장 중요)
추후 AWS China, Azure, GCP, NCP, OCI, SCP, Alibaba Cloud 등이 추가될 예정이므로 완전히 추상화된 인터페이스 기반으로 설계해야 한다.

### 2.1 인터페이스 설계 (Strategy Pattern)
- `CspPeeringService` (공통 인터페이스)
  - `PeeringResultDto createPeering(PeeringRequestDto request);`
  - `void deletePeering(String peeringId);`
- `AwsPeeringServiceImpl` (AWS 구현체 - 이번 단계에서 구현)
- `AwsChinaPeeringServiceImpl`, `AzurePeeringServiceImpl` (추후 확장용 스텁/주석 처리)

---

## 3. 상세 요구 기능 및 구현 프로세스

### [기능 1] VPC Peering 생성 및 네트워크 설정 프로세스
AWS SDK v2를 사용하여 아래 프로세스를 순차적 트랜잭션(또는 사가 패턴 형태)으로 실행한다.

1. **VPC Peering 요청 및 자동 수락**: 
   - `VpcPeeringConnection` 생성 요청 후, 동일 계정이므로 즉시 `AcceptVpcPeeringConnection` 호출하여 `Active` 상태로 전환.
2. **양방향 라우팅 테이블(Route Table) 업데이트**:
   - VPC-A의 라우팅 테이블에 대상 `10.2.0.0/16` ➡️ 생성된 `peering-id` 등록
   - VPC-B의 라우팅 테이블에 대상 `10.1.0.0/16` ➡️ 생성된 `peering-id` 등록
3. **보안 그룹(Security Group) 규칙 교차 등록**:
   - SG-B에 인바운드 규칙 추가: 모든 ICMP 트래픽 허용 ➡️ 소스: VPC-A 대역(`10.1.0.0/16`)

### [기능 2] VPC Peering 해제 및 복구 프로세스
피어링을 해제할 때 연관된 네트워크 자원을 역순으로 깔끔하게 정리한다.

1. **라우팅 테이블 규칙 삭제**: 양쪽 라우팅 테이블에서 Peering ID와 연관된 CIDR 경로 제거
2. **보안 그룹 규칙 삭제**: SG-B에 등록했던 `10.1.0.0/16` 대상 ICMP 인바운드 규칙 제거
3. **VPC Peering 연결 삭제**: 최종적으로 `DeleteVpcPeeringConnection` 호출

### [기능 3] MariaDB 기반 기록 및 스냅샷(Snapshot) 저장 프로세스
정상 리소스가 삭제되기 직전의 상태를 데이터베이스에 백업 및 로깅해야 한다.

1. **ERD 테이블 설계**:
   - `peering_history`: 피어링 ID, 요청자, 수락자, 상태(ACTIVE, DELETED), 생성/삭제일시
   - `network_snapshot`: 스냅샷 ID, 피어링 ID, 데이터 타입(ROUTE_TABLE / SECURITY_GROUP), 스냅샷 데이터(JSON 포맷 형식 저장)
2. **스냅샷 데이터 수집 및 저장**:
   - 삭제 API가 호출되면 AWS SDK를 통해 현재 라우팅 테이블 상태(`DescribeRouteTables`)와 보안 그룹 상태(`DescribeSecurityGroups`)를 조회한다.
   - 조회된 원본 객체를 **JSON 문자열 포맷**으로 변환하여 `network_snapshot` 테이블의 `blob` 또는 `longtext` 컬럼에 영구 저장한 후 삭제 프로세스를 진행한다.

### [기능 4] 프론트엔드 UI 화면 구현
사용자가 브라우저에서 제어할 수 있는 대시보드 화면을 구성한다.

1. **VPC Peering 생성 화면**:
   - 입력 폼: 요청자 VPC ID, 수락자 VPC ID, 각각의 CIDR 블록, 적용할 라우팅 테이블 ID 정보
   - [피어링 연결하기] 버튼
2. **VPC Peering 관리 및 삭제 화면**:
   - 현재 활성화된 피어링 목록 조회 테이블 (ID, VPC 정보, 상태, 생성일 출력)
   - 각 행 우측에 [피어링 해제 및 자원 삭제] 버튼 배치
   - 삭제된 피어링의 과거 내역과 당시 저장된 JSON 스냅샷을 조회할 수 있는 로그 뷰어 팝업 링크

---

## 4. 클로드에게 요청하는 산출물 범위

위 요구사항을 바탕으로 신규 Spring Boot 프로젝트를 구성할 수 있도록 아래 코드를 생성해 줘.

1. **`pom.xml`**: AWS SDK v2, Spring Data JPA, MariaDB Driver, Jackson(JSON 파싱용) 의존성이 포함된 파일
2. **도메인 엔티티 & 레포지토리**: `PeeringHistory.java`, `NetworkSnapshot.java` 및 JPA Repository 인터페이스
3. **구조적 서비스 레이어**: 멀티 CSP 확장을 위한 `CspPeeringService` 인터페이스와 AWS 구현체인 `AwsPeeringServiceImpl.java` (SDK v2 메서드 실구현 포함)
4. **컨트롤러 레이어**: 화면과 통신할 REST API 컨트롤러
5. **프론트엔드 코드**: 기능 4를 충족하는 HTML/JS 단일 파일 백오피스 소스코드 (Thymeleaf 기반 Bootstrap 레이아웃 또는 Vue3 Single File 중 택1)


AWS 인증 팩터 분리: 위 지시서를 주면 클로드가 AWS 클라이언트를 생성하는 코드를 만들어 줄 것입니다. 이때 서브 계정 User-A와 User-B 자격 증명(AccessKey/SecretKey)이 각각 필요하므로, application.yml에 두 계정의 키를 따로 설정할 수 있도록 구조를 잡아달라고 추가 요청하시는 것이 좋습니다.

트랜잭션 관리: AWS 리소스 생성은 DB처럼 완전한 롤백(Rollback)이 지원되지 않습니다. 라우팅 테이블 수정 중 에러가 나면 앞에 만든 피어링을 지워주는 보상 트랜잭션(Compensating Transaction) 코드를 넣어달라고 명시하면 코드가 훨씬 프로덕션급으로 견고해집니다.
