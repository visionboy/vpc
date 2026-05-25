# 1. AWS 프로바이더 설정
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-2" # 서울 리전
}

# 2. AWS 기존 키 페어 정보 조회
data "aws_key_pair" "existing_key" {
  key_name = "ec2A" # AWS 콘솔에 이미 등록된 키 페어 이름
}

# 공통 변수 관리 정의 (수정됨)
locals {
  my_home_ip = "210.96.170.246/32"
  ec2_c_ip   = "10.3.0.0/16" 
}

# ==========================================
# [보안 그룹] 각 VPC별 SSH(22포트) 허용 설정
# ==========================================

# VPC-A 전용 보안 그룹
resource "aws_security_group" "sg_a" {
  name        = "allow-ssh-vpc-a"
  description = "Allow SSH traffic for VPC-A"
  vpc_id      = "vpc-08e32962e391d05f8" # VPC-A ID

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [local.my_home_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "sg-vpc-a-ssh" }
}

# VPC-B 전용 보안 그룹
resource "aws_security_group" "sg_b" {
  name        = "allow-ssh-vpc-b"
  description = "Allow SSH traffic for VPC-B"
  vpc_id      = "vpc-0f859f1892b9a0902" # VPC-B ID

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [local.my_home_ip, local.ec2_c_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "sg-vpc-b-ssh" }
}

# VPC-C 전용 보안 그룹 (수정됨)
resource "aws_security_group" "sg_c" {
  name        = "allow-ssh-vpc-c"
  description = "Allow SSH traffic for VPC-C"
  vpc_id      = "vpc-027bde583207232fa" # VPC-C ID

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [local.my_home_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "sg-vpc-c-ssh" }
}


# ==========================================
# [VPC-A] EC2 인스턴스 설정 (퍼블릭 IP 활성화)
# ==========================================
resource "aws_instance" "ec2_a" {
  ami                         = "ami-010502f62836f0c67"
  instance_type               = "t3.micro"
  subnet_id                   = "subnet-01cea42a0ca5d94dd"
  key_name                    = data.aws_key_pair.existing_key.key_name
  associate_public_ip_address = true

  # ⚠️ 생성한 VPC-A 전용 보안 그룹 주입
  vpc_security_group_ids = [aws_security_group.sg_a.id]

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
  }

  credit_specification {
    cpu_credits = "standard"
  }

  tags = {
    Name    = "EC2-A"
    Project = "VPC-A"
  }
}


# ==========================================
# [VPC-B] EC2 인스턴스 설정 (퍼블릭 IP 비활성화)
# ==========================================
resource "aws_instance" "ec2_b" {
  ami                         = "ami-010502f62836f0c67"
  instance_type               = "t3.micro"
  subnet_id                   = "subnet-0b2c75777feda43fb"
  key_name                    = data.aws_key_pair.existing_key.key_name
  associate_public_ip_address = false

  # ⚠️ 생성한 VPC-B 전용 보안 그룹 주입
  vpc_security_group_ids = [aws_security_group.sg_b.id]

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
  }

  credit_specification {
    cpu_credits = "standard"
  }

  tags = {
    Name    = "EC2-B"
    Project = "VPC-B"
  }
}


# ==========================================
# [VPC-C] EC2 인스턴스 설정 (퍼블릭 IP 활성화)
# ==========================================
resource "aws_instance" "ec2_a_v3" {
  ami                         = "ami-010502f62836f0c67"
  instance_type               = "t3.micro"
  subnet_id                   = "subnet-00c6c769b2c629dc8"
  key_name                    = data.aws_key_pair.existing_key.key_name
  associate_public_ip_address = true

  # ⚠️ 생성한 VPC-C 전용 보안 그룹 주입
  vpc_security_group_ids = [aws_security_group.sg_c.id]

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
  }

  credit_specification {
    cpu_credits = "standard"
  }

  tags = {
    Name    = "EC2-C"
    Project = "VPC-A"
  }
}


# ==========================================
# [VPC-B] RDS MariaDB 설정
# ==========================================

# RDS MariaDB 인스턴스 생성
resource "aws_db_instance" "maria_db" {
  identifier             = "aws-maria"               # DB 식별자
  engine                 = "mariadb"                 # 엔진
  engine_version         = "10.11"                   # MariaDB 메이저 버전
  instance_class         = "db.t4g.micro"            # 클래스 크기
  allocated_storage      = 20                        # 기본 할당 용량 (GiB)
  max_allocated_storage  = 100                       # 스토리지 자동 확장 최대치

  username               = "admin"                   # 마스터 사용자 이름
  password               = "s123456A!"               # 마스터 비밀번호

  db_subnet_group_name   = "rds-subnet-group-b"      # 지정하신 서브넷 그룹 이름
  vpc_security_group_ids = ["sg-0d770546a5c29fcf4"]  # 사용 보안그룹 ID 주입
  availability_zone      = "ap-northeast-2a"         # 요청하신 리전 및 AZ 고정

  multi_az               = false                     # 다중 AZ: "아니요"
  publicly_accessible    = false                     # 외부 공개 차단
  skip_final_snapshot    = true                      # 삭제 시 최종 스냅샷 생성 스킵

  tags = {
    Name    = "aws-maria"
    Project = "VPC-B"
  }
}


# ==========================================
# [출력] 생성 완료 후 정보 확인용
# ==========================================
output "ec2_a_public_ip" {
  value       = aws_instance.ec2_a.public_ip
  description = "VPC-A EC2 Public IP"
}

output "ec2_c_public_ip" {
  value       = aws_instance.ec2_a_v3.public_ip
  description = "VPC-C EC2 Public IP"
}

# RDS 접속 엔드포인트 주소 출력
output "rds_endpoint" {
  value       = aws_db_instance.maria_db.endpoint
  description = "VPC-B RDS MariaDB Endpoint"
}