provider "aws" {
  region = var.region
}

locals {
  http_bind = var.http_public ? "0.0.0.0" : "127.0.0.1"

  # Deterministically inject deploy coordinates into the bootstrap via env
  # exports prepended to the committed script (avoids any tag-propagation race).
  # The script body keeps its own shebang (a harmless comment mid-file) and
  # reads ${NEMAKI_*:-default}, picking up these exported values. Values are
  # single-quoted so spaces / shell metacharacters in a variable cannot break
  # the export line (these are operator-set config, not untrusted input, but we
  # quote defensively). None of these values legitimately contain a single quote.
  bootstrap = file("${path.module}/../../aws/user-data.sh")
  # Optional post-bootstrap automation (Setup Wizard + Bedrock + Atlas + cloud
  # auth + Caddy HTTPS). Appended after the stock bootstrap; reads the CLOUD_AUTH_*
  # / NIP_HOST / BEDROCK_* exports below.
  fullconfig = var.enable_full_config ? file("${path.module}/../../aws/nemaki-full-config.sh") : ""
  user_data = <<-EOT
    #!/bin/bash
    export NEMAKI_REPO='${var.nemaki_repo}'
    export NEMAKI_REF='${var.nemaki_ref}'
    export NEMAKI_IMAGE_PREFIX='${var.nemaki_image_prefix}'
    export NEMAKI_VERSION='${var.nemaki_version}'
    export NEMAKI_HTTP_BIND='${local.http_bind}'
    export COUCHDB_USER='admin'
    export COUCHDB_SECRET_ID='${var.couchdb_secret_arn}'
    export NIP_HOST='${var.nip_host}'
    export CLOUD_AUTH_MICROSOFT_CLIENT_ID='${var.cloud_auth_microsoft_client_id}'
    export CLOUD_AUTH_MICROSOFT_TENANT_ID='${var.cloud_auth_microsoft_tenant_id}'
    export CLOUD_AUTH_GOOGLE_CLIENT_ID='${var.cloud_auth_google_client_id}'
    export BEDROCK_REGION='${var.bedrock_region}'
    export BEDROCK_MODEL_ID='${var.bedrock_model_id}'
    ${local.bootstrap}
    ${local.fullconfig}
  EOT

  vpc_id    = var.vpc_id != null ? var.vpc_id : try(data.aws_vpc.default[0].id, null)
  subnet_id = var.subnet_id != null ? var.subnet_id : try(data.aws_subnets.default[0].ids[0], null)

  common_tags = merge({ Project = "NemakiWare", ManagedBy = "terraform" }, var.tags)

  public_ip = var.eip_allocation_id != "" ? try(data.aws_eip.reused[0].public_ip, null) : (var.assign_eip ? try(aws_eip.this[0].public_ip, null) : aws_instance.this.public_ip)
}

# ── AMI: latest Amazon Linux 2023 (x86_64) via public SSM parameter ──────────
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# ── Default VPC/subnet fallback ──────────────────────────────────────────────
data "aws_vpc" "default" {
  count   = var.vpc_id == null ? 1 : 0
  default = true
}

data "aws_subnets" "default" {
  count = var.subnet_id == null ? 1 : 0
  filter {
    name   = "vpc-id"
    values = [local.vpc_id]
  }
}

# ── Security group ───────────────────────────────────────────────────────────
resource "aws_security_group" "this" {
  name_prefix = "${var.name}-"
  description = "NemakiWare ingress"
  vpc_id      = local.vpc_id
  tags        = merge(local.common_tags, { Name = var.name })

  lifecycle { create_before_destroy = true }
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  for_each          = toset(var.allowed_cidr_https)
  security_group_id = aws_security_group.this.id
  description       = "HTTPS"
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# 8080 opened only for the plain-HTTP demo posture.
resource "aws_vpc_security_group_ingress_rule" "http8080" {
  for_each          = var.http_public ? toset(var.allowed_cidr_https) : toset([])
  security_group_id = aws_security_group.this.id
  description       = "Core HTTP (demo)"
  cidr_ipv4         = each.value
  from_port         = 8080
  to_port           = 8080
  ip_protocol       = "tcp"
}

# 80 opened for the Caddy Let's Encrypt HTTP-01 challenge + http->https redirect
# (only when the full-config TLS proxy is enabled).
resource "aws_vpc_security_group_ingress_rule" "http80" {
  for_each          = var.enable_full_config ? toset(var.allowed_cidr_https) : toset([])
  security_group_id = aws_security_group.this.id
  description       = "HTTP (Caddy ACME/redirect)"
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each          = toset(var.allowed_cidr_ssh)
  security_group_id = aws_security_group.this.id
  description       = "SSH"
  cidr_ipv4         = each.value
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.this.id
  description       = "All egress"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# ── IAM role / instance profile (only needs Secrets Manager when configured) ─
data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  name_prefix        = "${var.name}-"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy" "secrets" {
  count = var.couchdb_secret_arn != "" ? 1 : 0
  name  = "couchdb-secret-read"
  role  = aws_iam_role.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = var.couchdb_secret_arn
    }]
  })
}

# When the full-config automation is enabled, let the instance call Amazon
# Bedrock via its instance profile (no static keys) so the RAG
# BedrockEmbeddingService can generate embeddings. Scoped to InvokeModel only.
resource "aws_iam_role_policy" "bedrock" {
  count = var.enable_full_config ? 1 : 0
  name  = "bedrock-invoke"
  role  = aws_iam_role.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
      Resource = "*"
    }]
  })
}

resource "aws_iam_instance_profile" "this" {
  name_prefix = "${var.name}-"
  role        = aws_iam_role.this.name
}

# ── Instance ─────────────────────────────────────────────────────────────────
resource "aws_instance" "this" {
  ami                         = data.aws_ssm_parameter.al2023.value
  instance_type               = var.instance_type
  key_name                    = var.key_name
  subnet_id                   = local.subnet_id
  vpc_security_group_ids      = [aws_security_group.this.id]
  iam_instance_profile        = aws_iam_instance_profile.this.name
  associate_public_ip_address = true
  user_data                   = local.user_data
  user_data_replace_on_change = true

  metadata_options {
    http_tokens   = "required" # IMDSv2 only
    http_endpoint = "enabled"
  }

  root_block_device {
    volume_size = var.root_volume_size
    volume_type = "gp3"
    encrypted   = true
  }

  tags = merge(local.common_tags, { Name = var.name })
}

# Fresh EIP — only when assign_eip AND no persistent EIP is being reused.
resource "aws_eip" "this" {
  count    = var.assign_eip && var.eip_allocation_id == "" ? 1 : 0
  instance = aws_instance.this.id
  domain   = "vpc"
  tags     = merge(local.common_tags, { Name = var.name })
}

# Reuse a persistent EIP (kept across destroy+apply) so the public IP / hostname
# stays stable — register the OAuth redirect URI / origin once and never again.
resource "aws_eip_association" "reused" {
  count         = var.eip_allocation_id != "" ? 1 : 0
  instance_id   = aws_instance.this.id
  allocation_id = var.eip_allocation_id
}

data "aws_eip" "reused" {
  count = var.eip_allocation_id != "" ? 1 : 0
  id    = var.eip_allocation_id
}
