output "role_arn" {
  value = aws_iam_role.this.arn
}

output "association_id" {
  value = aws_eks_pod_identity_association.this.association_id
}

