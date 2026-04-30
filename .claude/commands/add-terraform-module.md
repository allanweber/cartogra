You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Scaffold a Terraform module following Cartogra conventions: one module per resource group, remote state, tagging, validation.

Arguments: $ARGUMENTS
(Expected: `<module-name> [environment]` — e.g., `/add-terraform-module rds prod`)
(environment defaults to `dev` if omitted)

## Steps

1. **Parse arguments**: module name, environment (default: `dev`)

2. **Module directory**: `terraform/modules/<module-name>/`

3. **`variables.tf`**:
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
   # Example:
   # variable "db_password" {
   #   description = "Database master password"
   #   type        = string
   #   sensitive   = true
   # }
   ```

4. **`main.tf`**:
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

   # Add resources here
   # Use for_each over count for named resources:
   #   resource "aws_..." "example" {
   #     for_each = var.instances
   #     tags     = merge(local.common_tags, { Name = each.key })
   #   }
   ```

5. **`outputs.tf`**:
   ```hcl
   # Export resource identifiers needed by other modules
   # Example:
   # output "db_endpoint" {
   #   description = "RDS instance endpoint"
   #   value       = aws_db_instance.main.endpoint
   # }
   ```

6. **Environment wiring** at `terraform/environments/<environment>/main.tf` (add module call):
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
     # pass module-specific vars
   }
   ```

7. **Verify rules checklist:**
   - [ ] Module in `terraform/modules/<name>/` — NOT a monolith module
   - [ ] Remote state: S3 backend + DynamoDB locking + `encrypt = true`
   - [ ] Separate state key per environment (`<env>/<module>/terraform.tfstate`)
   - [ ] `for_each` used for named resources — NOT `count`
   - [ ] All sensitive variables: `sensitive = true`
   - [ ] No passwords/tokens hardcoded in `.tf` or `.tfvars`
   - [ ] All resources tagged: `Environment`, `Project`, `ManagedBy = Terraform`, `Owner`, `CostCenter`
   - [ ] `validation` blocks on enum variables (e.g., environment names, instance sizes)
   - [ ] `required_version` and `required_providers` with pinned version constraints
   - [ ] No `.tfvars` with secrets committed — use `terraform.tfvars.example` as template
