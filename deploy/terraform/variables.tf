variable "region" {
  type        = string
  description = "AWS region to deploy into"
  default     = "ap-south-1"
}

variable "vpc_id" {
  type        = string
  description = "VPC that holds the host and the database"
}

variable "public_subnet_id" {
  type        = string
  description = "Subnet with a route to an internet gateway for the host"
}

variable "database_subnet_ids" {
  type        = list(string)
  description = "At least two private subnets in different zones for the database"

  validation {
    condition     = length(var.database_subnet_ids) >= 2
    error_message = "the database needs subnets in at least two zones"
  }
}

variable "instance_type" {
  type        = string
  description = "Instance type of the host that runs the sandbox"
  default     = "m7i.xlarge"
}

variable "database_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "database_storage_gb" {
  type    = number
  default = 50
}

variable "work_volume_gb" {
  type        = number
  description = "Size of the volume that holds checkouts and the build cache"
  default     = 200
}

variable "server_image" {
  type        = string
  description = "Image of the Rook server, including the tag"
}

variable "dashboard_image" {
  type        = string
  description = "Image of the dashboard, including the tag"
}

variable "allowed_cidrs" {
  type        = list(string)
  description = "Networks that may reach the dashboard and the API"
}

variable "webhook_cidrs" {
  type        = list(string)
  description = "Networks GitHub delivers webhooks from"
  default     = ["192.30.252.0/22", "185.199.108.0/22", "140.82.112.0/20", "143.55.64.0/20"]
}

variable "github_app_id" {
  type    = string
  default = ""
}

variable "ssh_key_name" {
  type        = string
  description = "Key pair for the host, empty to rely on Session Manager only"
  default     = ""
}
