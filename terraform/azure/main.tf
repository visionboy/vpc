# 1. Azure 프로바이더 설정
terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.0"
    }
  }
}

provider "azurerm" {
  features {}
}

# 2. 모든 자원을 담을 리소스 그룹 생성 (삭제 시 이 그룹만 지우면 완벽히 청소됨)
resource "azurerm_resource_group" "rg" {
  name     = "rg-peering-study"
  location = "koreacentral" # 한국 중부 리전
}

# ==========================================
# [네트워크 A 구성]
# ==========================================
resource "azurerm_virtual_network" "vnet_a" {
  name                = "vnet-a"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  address_space       = ["10.1.0.0/16"]
}

resource "azurerm_subnet" "subnet_a" {
  name                 = "subnet-a"
  resource_group_name  = azurerm_resource_group.rg.name
  virtual_network_name = azurerm_virtual_network.vnet_a.name
  address_prefixes     = ["10.1.1.0/24"]
}

# ==========================================
# [네트워크 B 구성]
# ==========================================
resource "azurerm_virtual_network" "vnet_b" {
  name                = "vnet-b"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  address_space       = ["10.2.0.0/16"]
}

resource "azurerm_subnet" "subnet_b" {
  name                 = "subnet-b"
  resource_group_name  = azurerm_resource_group.rg.name
  virtual_network_name = azurerm_virtual_network.vnet_b.name
  address_prefixes     = ["10.2.1.0/24"]
}

# ==========================================
# [VNet Peering 양방향 설정]
# ==========================================
resource "azurerm_virtual_network_peering" "peer_a_to_b" {
  name                      = "peer-vnet-a-to-b"
  resource_group_name       = azurerm_resource_group.rg.name
  virtual_network_name      = azurerm_virtual_network.vnet_a.name
  remote_virtual_network_id = azurerm_virtual_network.vnet_b.id
}

resource "azurerm_virtual_network_peering" "peer_b_to_a" {
  name                      = "peer-vnet-b-to-a"
  resource_group_name       = azurerm_resource_group.rg.name
  virtual_network_name      = azurerm_virtual_network.vnet_b.name
  remote_virtual_network_id = azurerm_virtual_network.vnet_a.id
}

# ==========================================
# [보안 그룹(NSG) - SSH 및 Telnet 테스트 허용]
# ==========================================
resource "azurerm_network_security_group" "nsg" {
  name                = "nsg-peering-test"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location

  security_rule {
    name                       = "Allow-SSH"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = "*" # 실제 실습 시에는 본인 집 IP를 넣는 것이 안전합니다.
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "Allow-Telnet-Testing"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "*" # 모든 포트간 내부 통신 테스트용 오픈
    source_address_prefix      = "10.0.0.0/8" # 내부 가상 네트워크 대역 통신 허용
    destination_address_prefix = "*"
  }
}

# ==========================================
# [VM A 생성 (VNet A 소속)]
# ==========================================
resource "azurerm_public_ip" "pip_a" {
  name                = "pip-vm-a"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  allocation_method   = "Static"
  sku                 = "Basic"
}

resource "azurerm_network_interface" "nic_a" {
  name                = "nic-vm-a"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location

  ip_configuration {
    name                          = "internal"
    subnet_id                     = azurerm_subnet.subnet_a.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.pip_a.id
  }
}

resource "azurerm_network_interface_security_group_association" "nsg_assoc_a" {
  network_interface_id      = azurerm_network_interface.nic_a.id
  network_security_group_id = azurerm_network_security_group.nsg.id
}

resource "azurerm_linux_virtual_machine" "vm_a" {
  name                = "vm-a"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  size                = "Standard_B1ls" # 0.5GB RAM 최저가 사양 (시간당 약 7원)
  admin_username      = "azureuser"

  # 학습의 편의성을 위해 비밀번호 인증 방식으로 설정 (실무에서는 SSH Key 권장)
  disable_password_authentication = false
  admin_password                  = "Password1234!" # 본인의 패스워드로 변경하세요

  network_interface_ids = [azurerm_network_interface.nic_a.id]

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Standard_LRS" # 최저가 HDD 디스크 타입
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts"
    version   = "latest"
  }
}

# ==========================================
# [VM B 생성 (VNet B 소속)]
# ==========================================
resource "azurerm_public_ip" "pip_b" {
  name                = "pip-vm-b"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  allocation_method   = "Static"
  sku                 = "Basic"
}

resource "azurerm_network_interface" "nic_b" {
  name                = "nic-vm-b"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location

  ip_configuration {
    name                          = "internal"
    subnet_id                     = azurerm_subnet.subnet_b.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.pip_b.id
  }
}

resource "azurerm_network_interface_security_group_association" "nsg_assoc_b" {
  network_interface_id      = azurerm_network_interface.nic_b.id
  network_security_group_id = azurerm_network_security_group.nsg.id
}

resource "azurerm_linux_virtual_machine" "vm_b" {
  name                = "vm-b"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  size                = "Standard_B1ls" # 0.5GB RAM 최저가 사양
  admin_username      = "azureuser"

  disable_password_authentication = false
  admin_password                  = "Password1234!"

  network_interface_ids = [azurerm_network_interface.nic_b.id]

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Standard_LRS"
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts"
    version   = "latest"
  }
}

# ==========================================
# [가성비 데이터베이스 (PostgreSQL Flexible Server)]
# ==========================================
# DB가 VNet B 내부에 안전하게 프라이빗하게 위치하도록 서브넷 전용 위임 설정
resource "azurerm_subnet" "subnet_db" {
  name                 = "subnet-database"
  resource_group_name  = azurerm_resource_group.rg.name
  virtual_network_name = azurerm_virtual_network.vnet_b.name
  address_prefixes     = ["10.2.2.0/24"]
  
  delegation {
    name = "fs-delegation"
    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

# 프라이빗 DNS 존 (Flexible Server 필수 요구사항)
resource "azurerm_private_dns_zone" "dns_db" {
  name                = "peeringstudy.postgres.database.azure.com"
  resource_group_name = azurerm_resource_group.rg.name
}

resource "azurerm_private_dns_zone_virtual_network_link" "dns_link" {
  name                  = "db-dns-link"
  resource_group_name   = azurerm_resource_group.rg.name
  private_dns_zone_name = azurerm_private_dns_zone.dns_db.name
  virtual_network_id    = azurerm_virtual_network.vnet_b.id
}

resource "azurerm_postgresql_flexible_server" "db" {
  name                   = "db-peering-study-unique-psql" # Azure 전체에서 유일한 이름이어야 합니다.
  resource_group_name    = azurerm_resource_group.rg.name
  location               = azurerm_resource_group.rg.location
  version                = "14"
  delegated_subnet_id    = azurerm_subnet.subnet_db.id
  private_dns_zone_id    = azurerm_private_dns_zone.dns_db.id
  
  # 가장 저렴한 버스트 가능형 최하위 스펙 (개발/테스트용 고정 비용 최소화)
  sku_name   = "B_Standard_B1ms" 
  storage_mb = 32768 # 최소 용량 32GB

  administrator_login    = "dbadmin"
  administrator_password = "DBPassword1234!"

  zone = "1"

  depends_on = [azurerm_private_dns_zone_virtual_network_link.dns_link]
}

# ==========================================
# [출력값(Outputs) - VM 접속용 IP 확인]
# ==========================================
output "vm_a_public_ip" {
  value       = azurerm_public_ip.pip_a.ip_address
  description = "VM A의 공인 IP (SSH 접속용)"
}

output "vm_b_public_ip" {
  value       = azurerm_public_ip.pip_b.ip_address
  description = "VM B의 공인 IP (SSH 접속용)"
}

output "vm_b_private_ip" {
  value       = azurerm_linux_virtual_machine.vm_b.private_ip_address
  description = "VM B의 사설 IP (VM A에서 telnet 테스트할 대상)"
}

output "db_private_fqdn" {
  value       = azurerm_postgresql_flexible_server.db.fqdn
  description = "데이터베이스 접속용 내부 주소"
}