data "aws_ssm_parameter" "ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "random_password" "database" {
  length  = 32
  special = false
}

resource "random_password" "admin_key" {
  length  = 48
  special = false
}

resource "aws_security_group" "host" {
  name_prefix = "rook-host-"
  vpc_id      = var.vpc_id

  ingress {
    description = "dashboard"
    from_port   = 3001
    to_port     = 3001
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidrs
  }

  ingress {
    description = "api"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidrs
  }

  ingress {
    description = "github webhooks"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = var.webhook_cidrs
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "database" {
  name_prefix = "rook-db-"
  vpc_id      = var.vpc_id

  ingress {
    description     = "postgres from the host"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.host.id]
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_subnet_group" "this" {
  name       = "rook"
  subnet_ids = var.database_subnet_ids
}

resource "aws_db_instance" "this" {
  identifier                      = "rook"
  engine                          = "postgres"
  engine_version                  = "16"
  instance_class                  = var.database_instance_class
  allocated_storage               = var.database_storage_gb
  storage_type                    = "gp3"
  storage_encrypted               = true
  db_name                         = "rook"
  username                        = "rook"
  password                        = random_password.database.result
  db_subnet_group_name            = aws_db_subnet_group.this.name
  vpc_security_group_ids          = [aws_security_group.database.id]
  backup_retention_period         = 7
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "rook-final"
  performance_insights_enabled    = true
  auto_minor_version_upgrade      = true
  publicly_accessible             = false
  enabled_cloudwatch_logs_exports = ["postgresql"]
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "host" {
  name_prefix        = "rook-host-"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "ecr" {
  role       = aws_iam_role.host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

resource "aws_iam_instance_profile" "host" {
  name_prefix = "rook-host-"
  role        = aws_iam_role.host.name
}

resource "aws_instance" "host" {
  ami                    = nonsensitive(data.aws_ssm_parameter.ami.value)
  instance_type          = var.instance_type
  subnet_id              = var.public_subnet_id
  vpc_security_group_ids = [aws_security_group.host.id]
  iam_instance_profile   = aws_iam_instance_profile.host.name
  key_name               = var.ssh_key_name == "" ? null : var.ssh_key_name

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    server_image    = var.server_image
    dashboard_image = var.dashboard_image
    database_url    = "jdbc:postgresql://${aws_db_instance.this.address}:5432/rook"
    database_pass   = random_password.database.result
    admin_key       = random_password.admin_key.result
    github_app_id   = var.github_app_id
    registry        = split("/", var.server_image)[0]
    region          = var.region
  })

  user_data_replace_on_change = true

  tags = {
    Name = "rook"
  }
}

resource "aws_ebs_volume" "work" {
  availability_zone = aws_instance.host.availability_zone
  size              = var.work_volume_gb
  type              = "gp3"
  encrypted         = true

  tags = {
    Name = "rook-work"
  }
}

resource "aws_volume_attachment" "work" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.work.id
  instance_id = aws_instance.host.id
}

resource "aws_eip" "host" {
  instance = aws_instance.host.id
  domain   = "vpc"
}
