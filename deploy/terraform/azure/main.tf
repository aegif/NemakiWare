locals {
  http_bind = var.http_public ? "0.0.0.0" : "127.0.0.1"

  # Deterministic config injection (same approach as the AWS module): prepend
  # env exports to the committed bootstrap; its ${NEMAKI_*:-default} reads pick
  # them up. custom_data must be base64-encoded.
  # Values are single-quoted so spaces / shell metacharacters cannot break the
  # export line. None of these values legitimately contain a single quote.
  bootstrap = file("${path.module}/../../azure/custom-data.sh")
  user_data = <<-EOT
    #!/bin/bash
    export NEMAKI_REPO='${var.nemaki_repo}'
    export NEMAKI_REF='${var.nemaki_ref}'
    export NEMAKI_IMAGE_PREFIX='${var.nemaki_image_prefix}'
    export NEMAKI_VERSION='${var.nemaki_version}'
    export NEMAKI_HTTP_BIND='${local.http_bind}'
    export COUCHDB_USER='admin'
    export COUCHDB_KEYVAULT_SECRET_URI='${var.couchdb_keyvault_secret_uri}'
    ${local.bootstrap}
  EOT

  use_identity = var.couchdb_keyvault_secret_uri != ""
  common_tags  = merge({ Project = "NemakiWare", ManagedBy = "terraform" }, var.tags)
}

# ── Resource group (create or reuse) ─────────────────────────────────────────
resource "azurerm_resource_group" "this" {
  count    = var.use_existing_rg ? 0 : 1
  name     = var.resource_group_name
  location = var.location
  tags     = local.common_tags
}

data "azurerm_resource_group" "existing" {
  count = var.use_existing_rg ? 1 : 0
  name  = var.resource_group_name
}

locals {
  rg_name     = var.use_existing_rg ? data.azurerm_resource_group.existing[0].name : azurerm_resource_group.this[0].name
  rg_location = var.use_existing_rg ? data.azurerm_resource_group.existing[0].location : azurerm_resource_group.this[0].location
}

# ── Network ──────────────────────────────────────────────────────────────────
resource "azurerm_virtual_network" "this" {
  name                = "${var.name}-vnet"
  address_space       = ["10.42.0.0/16"]
  location            = local.rg_location
  resource_group_name = local.rg_name
  tags                = local.common_tags
}

resource "azurerm_subnet" "this" {
  name                 = "${var.name}-subnet"
  resource_group_name  = local.rg_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = ["10.42.1.0/24"]
}

resource "azurerm_public_ip" "this" {
  name                = "${var.name}-pip"
  location            = local.rg_location
  resource_group_name = local.rg_name
  allocation_method   = "Static"
  sku                 = "Standard"
  tags                = local.common_tags
}

resource "azurerm_network_security_group" "this" {
  name                = "${var.name}-nsg"
  location            = local.rg_location
  resource_group_name = local.rg_name
  tags                = local.common_tags
}

resource "azurerm_network_security_rule" "https" {
  name                        = "allow-https"
  priority                    = 1001
  direction                   = "Inbound"
  access                      = "Allow"
  protocol                    = "Tcp"
  source_port_range           = "*"
  destination_port_range      = "443"
  source_address_prefixes     = var.allowed_cidr_https
  destination_address_prefix  = "*"
  resource_group_name         = local.rg_name
  network_security_group_name = azurerm_network_security_group.this.name
}

resource "azurerm_network_security_rule" "http8080" {
  count                       = var.http_public ? 1 : 0
  name                        = "allow-http-8080"
  priority                    = 1002
  direction                   = "Inbound"
  access                      = "Allow"
  protocol                    = "Tcp"
  source_port_range           = "*"
  destination_port_range      = "8080"
  source_address_prefixes     = var.allowed_cidr_https
  destination_address_prefix  = "*"
  resource_group_name         = local.rg_name
  network_security_group_name = azurerm_network_security_group.this.name
}

resource "azurerm_network_security_rule" "ssh" {
  count                       = length(var.allowed_cidr_ssh) > 0 ? 1 : 0
  name                        = "allow-ssh"
  priority                    = 1003
  direction                   = "Inbound"
  access                      = "Allow"
  protocol                    = "Tcp"
  source_port_range           = "*"
  destination_port_range      = "22"
  source_address_prefixes     = var.allowed_cidr_ssh
  destination_address_prefix  = "*"
  resource_group_name         = local.rg_name
  network_security_group_name = azurerm_network_security_group.this.name
}

resource "azurerm_network_interface" "this" {
  name                = "${var.name}-nic"
  location            = local.rg_location
  resource_group_name = local.rg_name
  tags                = local.common_tags

  ip_configuration {
    name                          = "ipconfig"
    subnet_id                     = azurerm_subnet.this.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.this.id
  }
}

resource "azurerm_network_interface_security_group_association" "this" {
  network_interface_id      = azurerm_network_interface.this.id
  network_security_group_id = azurerm_network_security_group.this.id
}

# ── Virtual machine ──────────────────────────────────────────────────────────
resource "azurerm_linux_virtual_machine" "this" {
  name                  = "${var.name}-vm"
  location              = local.rg_location
  resource_group_name   = local.rg_name
  size                  = var.vm_size
  admin_username        = var.admin_username
  network_interface_ids = [azurerm_network_interface.this.id]
  custom_data           = base64encode(local.user_data)
  tags                  = local.common_tags

  admin_ssh_key {
    username   = var.admin_username
    public_key = var.ssh_public_key
  }

  dynamic "identity" {
    for_each = local.use_identity ? [1] : []
    content {
      type = "SystemAssigned"
    }
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Premium_LRS"
    disk_size_gb         = var.os_disk_size_gb
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts-gen2"
    version   = "latest"
  }
}
