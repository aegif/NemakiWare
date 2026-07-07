output "public_ip" {
  description = "Public IP of the instance (reused persistent EIP, fresh EIP, or instance IP)."
  value       = local.public_ip
}

output "instance_id" {
  description = "EC2 instance id."
  value       = aws_instance.this.id
}

output "core_url" {
  description = "Core endpoint. HTTPS via Caddy/nip.io when full-config; else http(8080) demo or TLS-proxy note."
  value = var.enable_full_config && var.nip_host != "" ? "https://${var.nip_host}/core/ui/index.html" : (
    var.http_public ? "http://${local.public_ip}:8080/core" : "bound to 127.0.0.1:8080 — front with a TLS reverse proxy"
  )
}

output "https_host" {
  description = "Stable HTTPS hostname (register this once as the OAuth redirect origin)."
  value       = var.nip_host != "" ? var.nip_host : "n/a (no nip_host)"
}

output "ssh" {
  description = "SSH hint (if a key pair was set)."
  value       = var.key_name != null ? "ssh ec2-user@${local.public_ip}" : "no key_name set"
}
