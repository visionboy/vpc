# 초기화 (최초 1회):
terraform init

# 실행 전 미리보기
terraform plan

# 테라폼 문법 확인
terraform validate

# 코드 자동 정렬 및 예쁘게 다듬기
terraform fmt

# 현재 생성된 실제 자원 목록 보기
terraform state list

# 인프라 상태 상세 조회(현재 구축된 인프라의 모든 세부 설정 정보(IP 주소, 디스크 크기, ARN 값 등)를 인쇄하듯 화면에 통째로 보여줍니다.)
terraform show

# 테라폼 실행
terraform apply -auto-approve

# 삭제 명령어
terraform destroy -auto-approve

# 삭제가 잘 되었는지 확인하는 방법
# Destroy complete! Resources: 3 destroyed.

# telnet 설치
# sudo yum install telnet -y


