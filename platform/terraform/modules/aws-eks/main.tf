data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  common_tags = merge(var.tags, {
    "Name"       = var.name
    "ManagedBy"  = "terraform"
    "Repository" = "Mundia_library/platform"
  })
}

data "aws_iam_policy_document" "cluster_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name_prefix        = "${var.name}-cluster-"
  assume_role_policy = data.aws_iam_policy_document.cluster_assume.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

data "aws_iam_policy_document" "kms" {
  statement {
    sid       = "RootAccountAdministration"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }

  statement {
    sid    = "AllowEKSUse"
    effect = "Allow"
    actions = [
      "kms:CreateGrant",
      "kms:Decrypt",
      "kms:DescribeKey",
      "kms:Encrypt",
      "kms:GenerateDataKey*",
      "kms:ReEncrypt*",
    ]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = [aws_iam_role.cluster.arn]
    }
    condition {
      test     = "Bool"
      variable = "kms:GrantIsForAWSResource"
      values   = ["true"]
    }
  }

  statement {
    sid    = "AllowCloudWatchLogsUse"
    effect = "Allow"
    actions = [
      "kms:Decrypt*",
      "kms:Encrypt*",
      "kms:GenerateDataKey*",
      "kms:ReEncrypt*",
    ]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["logs.${data.aws_region.current.region}.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values = [
        "arn:aws:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/eks/${var.name}/cluster:*"
      ]
    }
  }
}

resource "aws_kms_key" "cluster" {
  description             = "EKS secret envelope encryption for ${var.name}"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  policy                  = data.aws_iam_policy_document.kms.json
  tags                    = local.common_tags
}

resource "aws_kms_alias" "cluster" {
  name          = "alias/${var.name}-eks-secrets"
  target_key_id = aws_kms_key.cluster.key_id
}

resource "aws_cloudwatch_log_group" "cluster" {
  name              = "/aws/eks/${var.name}/cluster"
  retention_in_days = 90
  kms_key_id        = aws_kms_key.cluster.arn
  skip_destroy      = true
  tags              = local.common_tags
}

resource "aws_eks_cluster" "this" {
  name     = var.name
  role_arn = aws_iam_role.cluster.arn
  version  = var.kubernetes_version

  bootstrap_self_managed_addons = false
  enabled_cluster_log_types = [
    "api",
    "audit",
    "authenticator",
    "controllerManager",
    "scheduler",
  ]

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = false
  }

  encryption_config {
    resources = ["secrets"]
    provider {
      key_arn = aws_kms_key.cluster.arn
    }
  }

  vpc_config {
    endpoint_private_access = true
    endpoint_public_access  = var.endpoint_public_access
    public_access_cidrs     = var.endpoint_public_access ? var.public_access_cidrs : []
    subnet_ids              = var.private_subnet_ids
  }

  tags = local.common_tags

  depends_on = [
    aws_cloudwatch_log_group.cluster,
    aws_iam_role_policy_attachment.cluster,
  ]

  lifecycle {
    precondition {
      condition     = var.node_min_size <= var.node_desired_size && var.node_desired_size <= var.node_max_size
      error_message = "Node scaling must satisfy min <= desired <= max."
    }
    precondition {
      condition     = !var.endpoint_public_access || length(var.public_access_cidrs) > 0
      error_message = "Public EKS API access requires at least one explicitly approved CIDR."
    }
  }
}

resource "aws_eks_access_entry" "administrator" {
  for_each = var.administrator_principal_arns

  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "administrator" {
  for_each = var.administrator_principal_arns

  cluster_name  = aws_eks_cluster.this.name
  principal_arn = aws_eks_access_entry.administrator[each.key].principal_arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }
}

data "aws_iam_policy_document" "node_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name_prefix        = "${var.name}-node-"
  assume_role_policy = data.aws_iam_policy_document.node_assume.json
  tags               = local.common_tags
}

locals {
  node_policy_arns = toset([
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
  ])
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = local.node_policy_arns

  role       = aws_iam_role.node.name
  policy_arn = each.value
}

resource "aws_launch_template" "node" {
  name_prefix            = "${var.name}-node-"
  update_default_version = true

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      delete_on_termination = true
      encrypted             = true
      volume_size           = 80
      volume_type           = "gp3"
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 1
    http_tokens                 = "required"
    instance_metadata_tags      = "disabled"
  }

  monitoring {
    enabled = true
  }

  tag_specifications {
    resource_type = "instance"
    tags          = merge(local.common_tags, { Name = "${var.name}-worker" })
  }

  tags = local.common_tags
}

resource "aws_eks_node_group" "system" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "system"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.private_subnet_ids
  version         = var.kubernetes_version
  capacity_type   = var.node_capacity_type
  instance_types  = var.node_instance_types

  launch_template {
    id      = aws_launch_template.node.id
    version = aws_launch_template.node.latest_version
  }

  scaling_config {
    min_size     = var.node_min_size
    desired_size = var.node_desired_size
    max_size     = var.node_max_size
  }

  update_config {
    max_unavailable_percentage = 25
  }

  labels = {
    "mundiapolis.io/node-pool" = "system"
  }

  tags = local.common_tags

  depends_on = [
    aws_iam_role_policy_attachment.node,
    aws_eks_addon.pod_identity_agent,
    aws_eks_addon.vpc_cni,
  ]
}

data "aws_iam_policy_document" "pod_identity_assume" {
  statement {
    effect = "Allow"
    actions = [
      "sts:AssumeRole",
      "sts:TagSession",
    ]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "vpc_cni" {
  name_prefix        = "${var.name}-vpc-cni-"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_assume.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "vpc_cni" {
  role       = aws_iam_role.vpc_cni.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}

resource "aws_eks_addon" "pod_identity_agent" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "eks-pod-identity-agent"
  addon_version               = var.addon_versions["eks-pod-identity-agent"]
  resolve_conflicts_on_create = "NONE"
  resolve_conflicts_on_update = "PRESERVE"
  tags                        = local.common_tags

}

resource "aws_eks_pod_identity_association" "vpc_cni" {
  cluster_name    = aws_eks_cluster.this.name
  namespace       = "kube-system"
  service_account = "aws-node"
  role_arn        = aws_iam_role.vpc_cni.arn

  depends_on = [aws_eks_addon.pod_identity_agent]
}

resource "aws_eks_addon" "vpc_cni" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "vpc-cni"
  addon_version               = var.addon_versions["vpc-cni"]
  resolve_conflicts_on_create = "NONE"
  resolve_conflicts_on_update = "PRESERVE"
  tags                        = local.common_tags

  depends_on = [aws_eks_pod_identity_association.vpc_cni]
}

resource "aws_eks_addon" "managed" {
  for_each = toset(["coredns", "kube-proxy"])

  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = each.key
  addon_version               = var.addon_versions[each.key]
  resolve_conflicts_on_create = "NONE"
  resolve_conflicts_on_update = "PRESERVE"
  tags                        = local.common_tags

  depends_on = [
    aws_eks_node_group.system,
  ]
}
