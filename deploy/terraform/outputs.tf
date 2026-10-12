output "address" {
  value       = aws_eip.host.public_ip
  description = "Public address of the host"
}

output "dashboard_url" {
  value = "http://${aws_eip.host.public_ip}:3001"
}

output "webhook_url" {
  value = "http://${aws_eip.host.public_ip}:8080/webhooks/github"
}

output "admin_key" {
  value     = random_password.admin_key.result
  sensitive = true
}

output "database_address" {
  value = aws_db_instance.this.address
}
