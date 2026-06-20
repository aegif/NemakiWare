output "public_ip" {
  description = "Public IP of the VM."
  value       = azurerm_public_ip.this.ip_address
}

output "vm_id" {
  description = "Virtual machine resource id."
  value       = azurerm_linux_virtual_machine.this.id
}

output "core_url" {
  description = "Core endpoint (when http_public). Otherwise front with TLS."
  value       = var.http_public ? "http://${azurerm_public_ip.this.ip_address}:8080/core" : "bound to 127.0.0.1:8080 — front with a TLS reverse proxy / App Gateway"
}

output "ssh" {
  description = "SSH hint."
  value       = "ssh ${var.admin_username}@${azurerm_public_ip.this.ip_address}"
}

output "identity_principal_id" {
  description = "System-assigned identity principal id (when Key Vault is used). Grant it 'get' on the vault secret."
  value       = local.use_identity ? azurerm_linux_virtual_machine.this.identity[0].principal_id : null
}
