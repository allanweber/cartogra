---
mode: 'agent'
description: 'Scaffold a Terraform module with remote state, tagging, and validation'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Scaffold a Terraform module following Cartogra conventions: one module per resource group, remote state, tagging, validation.

**Usage:** provide `<module-name> [environment]` — environment defaults to `dev` (e.g., `rds prod`)

## Steps

1. **Module directory**: `infra/terraform/modules/<module-name>/`

### `variables.tf`
```hcl
variable "environment" {
  description = "Deployment environment"
  type        = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of: dev, staging, prod."
  }
}
variable "project" {
  description = "Project name for tagging"
  type        = string
  default     = "cartogra"
}
variable "owner" {
  description = "Team or person responsible for this resource"
  type        = string
}
variable "cost_center" {
  description = "Cost center for billing"
  type        = string
}
# Add module-specific variables here
# Mark sensitive values: sensitive = true
```

### `main.tf`
```hcl
terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

locals {
  common_tags = {
    Environment = var.environment
    Project     = var.project
    ManagedBy   = "Terraform"
    Owner       = var.owner
    CostCenter  = var.cost_center
  }
}

# Add resources here — use for_each over count for named resources:
# resource "aws_..." "example" {
#   for_each = var.instances
#   tags     = merge(local.common_tags, { Name = each.key })
# }
```

### `outputs.tf`
```hcl
# Export resource identifiers needed by other modules
```

2. **Environment wiring** at `infra/terraform/environments/<environment>/main.tf`:
```hcl
terraform {
  backend "s3" {
    bucket         = "cartogra-terraform-state"
    key            = "<environment>/<module-name>/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "cartogra-terraform-locks"
  }
}

module "<module-name>" {
  source      = "../../modules/<module-name>"
  environment = "<environment>"
  owner       = "platform"
  cost_center = "engineering"
}
```

## Verify rules checklist
- [ ] Module in `terraform/modules/<name>/` — NOT a monolith module
- [ ] Remote state: S3 backend + DynamoDB locking + `encrypt = true`
- [ ] Separate state key per environment
- [ ] `for_each` used for named resources — NOT `count`
- [ ] All sensitive variables: `sensitive = true`
- [ ] No passwords/tokens hardcoded
- [ ] All resources tagged: `Environment`, `Project`, `ManagedBy = Terraform`, `Owner`, `CostCenter`
- [ ] `validation` blocks on enum variables
- [ ] No `.tfvars` with secrets committed
