variable "location" {
  description = "Azure region."
  type        = string
  default     = "japaneast"
}

variable "name" {
  description = "Name prefix for created resources."
  type        = string
  default     = "nemakiware"
}

variable "resource_group_name" {
  description = "Resource group name. Created unless use_existing_rg=true."
  type        = string
  default     = "nemakiware-rg"
}

variable "use_existing_rg" {
  description = "Use an existing resource group instead of creating one."
  type        = bool
  default     = false
}

variable "vm_size" {
  description = "VM size. Standard_B2ms (8GB) min; Standard_D4s_v5 (16GB) for RAG."
  type        = string
  default     = "Standard_B2ms"
}

variable "admin_username" {
  description = "Admin (SSH) username."
  type        = string
  default     = "azureuser"
}

variable "ssh_public_key" {
  description = "SSH public key material (contents of id_rsa.pub / id_ed25519.pub)."
  type        = string
}

variable "os_disk_size_gb" {
  description = "OS disk size in GB."
  type        = number
  default     = 50
}

variable "allowed_cidr_https" {
  description = "Source CIDRs allowed to reach 443 (and 8080 when http_public)."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "allowed_cidr_ssh" {
  description = "Source CIDRs allowed to reach 22. Empty disables SSH ingress."
  type        = list(string)
  default     = []
}

variable "http_public" {
  description = <<-EOT
    If true, open 8080 and bind core to 0.0.0.0 (plain-HTTP demo). If false
    (recommended), core binds 127.0.0.1 and you front it with App Gateway/TLS.
  EOT
  type        = bool
  default     = false
}

# ── NemakiWare deploy coordinates ─────────────────────────────────────────────
variable "nemaki_repo" {
  description = "GitHub owner/repo to clone the deploy tree from."
  type        = string
  default     = "aegif/NemakiWare"
}

variable "nemaki_ref" {
  description = "git tag/branch to deploy."
  type        = string
  default     = "v3.2.0"
}

variable "nemaki_image_prefix" {
  description = "Registry image prefix, e.g. ghcr.io/<owner>/nemakiware."
  type        = string
  default     = "ghcr.io/aegif/nemakiware"
}

variable "nemaki_version" {
  description = "Image tag to run (e.g. 3.2.0 or latest)."
  type        = string
  default     = "3.2.0"
}

variable "couchdb_keyvault_secret_uri" {
  description = <<-EOT
    Optional Key Vault secret URI for the CouchDB password, e.g.
    https://my-vault.vault.azure.net/secrets/couchdb-password. When set, the VM
    gets a system-assigned identity and the bootstrap reads it via IMDS. You
    must grant that identity 'get' on the vault separately. Empty = random.
  EOT
  type        = string
  default     = ""
}

variable "tags" {
  description = "Extra tags merged onto all resources."
  type        = map(string)
  default     = {}
}
