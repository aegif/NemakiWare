output "public_ip" {
  description = "Public IP of the instance (Elastic IP if assign_eip)."
  value       = var.assign_eip ? aws_eip.this[0].public_ip : aws_instance.this.public_ip
}

output "instance_id" {
  description = "EC2 instance id."
  value       = aws_instance.this.id
}

output "core_url" {
  description = "Core endpoint (when http_public). Otherwise front with TLS proxy."
  value       = var.http_public ? "http://${var.assign_eip ? aws_eip.this[0].public_ip : aws_instance.this.public_ip}:8080/core" : "bound to 127.0.0.1:8080 — front with a TLS reverse proxy"
}

output "ssh" {
  description = "SSH hint (if a key pair was set)."
  value       = var.key_name != null ? "ssh ec2-user@${var.assign_eip ? aws_eip.this[0].public_ip : aws_instance.this.public_ip}" : "no key_name set"
}
