variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "ap-northeast-1"
}

variable "name" {
  description = "Name prefix for created resources."
  type        = string
  default     = "nemakiware"
}

variable "instance_type" {
  description = "EC2 instance type. t3.large (8GB) min; t3.xlarge (16GB) for RAG."
  type        = string
  default     = "t3.large"
}

variable "key_name" {
  description = "Existing EC2 key pair name for SSH (optional)."
  type        = string
  default     = null
}

variable "vpc_id" {
  description = "VPC to launch into. Defaults to the region's default VPC."
  type        = string
  default     = null
}

variable "subnet_id" {
  description = "Subnet to launch into. Defaults to a default-VPC subnet."
  type        = string
  default     = null
}

variable "root_volume_size" {
  description = "Root EBS volume size in GB (gp3)."
  type        = number
  default     = 50
}

variable "assign_eip" {
  description = "Allocate and associate an Elastic IP."
  type        = bool
  default     = true
}

variable "allowed_cidr_https" {
  description = "CIDRs allowed to reach 443 (and 8080 when http_public)."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "allowed_cidr_ssh" {
  description = "CIDRs allowed to reach 22. Empty list disables SSH ingress."
  type        = list(string)
  default     = []
}

variable "http_public" {
  description = <<-EOT
    If true, open 8080 to allowed_cidr_https and bind core to 0.0.0.0
    (plain-HTTP demo). If false (recommended), core binds to 127.0.0.1 and you
    front it with a TLS reverse proxy / ALB on 443.
  EOT
  type        = bool
  default     = false
}

# ── NemakiWare deploy coordinates (passed to the bootstrap via instance tags) ─
variable "nemaki_repo" {
  description = "GitHub owner/repo to clone the deploy tree from."
  type        = string
  default     = "aegif/NemakiWare"
}

variable "nemaki_ref" {
  description = "git tag/branch to deploy (should match the image version)."
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

variable "couchdb_secret_arn" {
  description = <<-EOT
    Optional AWS Secrets Manager secret ARN holding the CouchDB password. When
    set, an IAM policy granting secretsmanager:GetSecretValue on exactly this
    ARN is attached and the bootstrap reads the password from it. Must be a full
    ARN (the IAM Resource and the CLI lookup both need it). Empty = random
    password generated on the host.
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.couchdb_secret_arn == "" || can(regex("^arn:aws[a-z-]*:secretsmanager:", var.couchdb_secret_arn))
    error_message = "couchdb_secret_arn must be empty or a full Secrets Manager ARN (arn:aws:secretsmanager:...)."
  }
}

variable "tags" {
  description = "Extra tags merged onto all resources."
  type        = map(string)
  default     = {}
}
