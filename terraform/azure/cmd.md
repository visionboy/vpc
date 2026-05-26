# EC2 아이디 비번
ID: azureuser
PW: Password1234!

# 테라폼 실행 및 자원 생성
terraform init
terraform apply

# VNet Peering 통신 테스트 (VM A ➡️ VM B)
# telnet 패키지 설치 (Ubuntu 기준)
sudo apt-get update && sudo apt-get install telnet -y

# VM B의 22번(SSH) 포트로 통신이 가는지 테스트
telnet [VM_B의_사설_IP] 22

# 실습 종료 후 완전 삭제 (비용 방지 필수)
terraform destroy