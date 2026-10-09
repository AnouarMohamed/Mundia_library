output "cluster_arn" {
  value = aws_msk_cluster.this.arn
}

output "bootstrap_brokers_sasl_scram" {
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
  description = "Private TLS/SCRAM bootstrap endpoints; credentials remain in Secrets Manager."
  sensitive   = true
}

output "broker_security_group_id" {
  value = aws_security_group.broker.id
}

output "broker_log_group_name" {
  value = aws_cloudwatch_log_group.broker.name
}
