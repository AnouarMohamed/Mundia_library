data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  availability_zones = slice(
    sort(data.aws_availability_zones.available.names),
    0,
    var.availability_zone_count,
  )
  az_map = {
    for index, az in local.availability_zones : tostring(index) => {
      index = index
      name  = az
    }
  }
  nat_gateway_keys = var.nat_gateway_per_az ? toset(keys(local.az_map)) : toset(["0"])
  common_tags = merge(var.tags, {
    "Name"       = var.name
    "ManagedBy"  = "terraform"
    "Repository" = "Mundia_library/platform"
  })
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = merge(local.common_tags, {
    "kubernetes.io/cluster/${var.name}" = "shared"
  })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.common_tags, { Name = "${var.name}-igw" })
}

resource "aws_subnet" "public" {
  for_each = local.az_map

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.value.name
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, each.value.index)
  map_public_ip_on_launch = false

  tags = merge(local.common_tags, {
    Name                                = "${var.name}-public-${each.value.name}"
    "kubernetes.io/role/elb"            = "1"
    "kubernetes.io/cluster/${var.name}" = "shared"
    Tier                                = "public-edge"
  })
}

resource "aws_subnet" "private" {
  for_each = local.az_map

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.value.name
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, each.value.index + 8)
  map_public_ip_on_launch = false

  tags = merge(local.common_tags, {
    Name                                = "${var.name}-private-${each.value.name}"
    "kubernetes.io/role/internal-elb"   = "1"
    "kubernetes.io/cluster/${var.name}" = "shared"
    Tier                                = "private-workload"
  })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.common_tags, { Name = "${var.name}-public" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  for_each = local.nat_gateway_keys

  domain = "vpc"
  tags   = merge(local.common_tags, { Name = "${var.name}-nat-${each.key}" })

  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  for_each = local.nat_gateway_keys

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id
  tags          = merge(local.common_tags, { Name = "${var.name}-nat-${each.key}" })
}

resource "aws_route_table" "private" {
  for_each = local.az_map

  vpc_id = aws_vpc.this.id
  tags   = merge(local.common_tags, { Name = "${var.name}-private-${each.value.name}" })
}

resource "aws_route" "private_egress" {
  for_each = local.az_map

  route_table_id         = aws_route_table.private[each.key].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id = aws_nat_gateway.this[
    var.nat_gateway_per_az ? each.key : "0"
  ].id
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private[each.key].id
}

resource "aws_security_group" "endpoints" {
  name_prefix = "${var.name}-endpoints-"
  description = "TLS from workloads to private AWS service endpoints"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "TLS from the VPC"
    protocol    = "tcp"
    from_port   = 443
    to_port     = 443
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Return traffic"
    protocol    = "-1"
    from_port   = 0
    to_port     = 0
    cidr_blocks = [var.vpc_cidr]
  }

  tags = merge(local.common_tags, { Name = "${var.name}-service-endpoints" })
}

locals {
  interface_endpoints = toset([
    "ecr.api",
    "ecr.dkr",
    "logs",
    "secretsmanager",
    "ssm",
    "sts",
  ])
}

resource "aws_vpc_endpoint" "interface" {
  for_each = local.interface_endpoints

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${data.aws_region.current.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = values(aws_subnet.private)[*].id
  security_group_ids  = [aws_security_group.endpoints.id]

  tags = merge(local.common_tags, { Name = "${var.name}-${replace(each.value, ".", "-")}" })
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = values(aws_route_table.private)[*].id

  tags = merge(local.common_tags, { Name = "${var.name}-s3" })
}

data "aws_region" "current" {}

data "aws_iam_policy_document" "flow_logs_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "flow_logs" {
  name_prefix        = "${var.name}-flow-logs-"
  assume_role_policy = data.aws_iam_policy_document.flow_logs_assume.json
  tags               = local.common_tags
}

data "aws_iam_policy_document" "flow_logs" {
  statement {
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:DescribeLogGroups",
      "logs:DescribeLogStreams",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.flow_logs.arn}:*"]
  }
}

resource "aws_iam_role_policy" "flow_logs" {
  name   = "publish-vpc-flow-logs"
  role   = aws_iam_role.flow_logs.id
  policy = data.aws_iam_policy_document.flow_logs.json
}

resource "aws_cloudwatch_log_group" "flow_logs" {
  name              = "/aws/vpc-flow-log/${var.name}"
  retention_in_days = var.flow_log_retention_days
  kms_key_id        = var.flow_log_kms_key_arn
  skip_destroy      = true
  tags              = local.common_tags
}

resource "aws_flow_log" "this" {
  vpc_id                   = aws_vpc.this.id
  traffic_type             = "ALL"
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flow_logs.arn
  iam_role_arn             = aws_iam_role.flow_logs.arn
  max_aggregation_interval = 60

  tags = local.common_tags
}
