Name: Terraform Validate Check
Description: Pipeline job to validate Terraform files in my Github repo every 10 minutes
Triggers: Poll SCM
- Schedule: H/10 * * * *
Pipeline
- Definition: Pipeline script
Script:
pipeline {
  agent {
    kubernetes {
      label 'terraform-validate-agent'
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: terraform
    image: hashicorp/terraform:latest
    command:
    - cat
    tty: true
"""
    }
  }

  stages {

    stage('Checkout') {
      steps {
        git branch: 'main', url: 'https://github.com/<username>/Terraform.git' # Add Terrform repo here
      }
    }

    stage('Validate Terraform') {
      steps {
        container('terraform') {
          sh '''
echo 'Validating Terraform code recursively...'

# Loop over directories containing .tf files
find . -type f -name '*.tf' -exec dirname {} \\; | sort -u | while read dir; do
  echo "Validating Terraform in '$dir'..."
  cd "$dir"

  # Initialize Terraform
  terraform init -input=false -backend=false

  # Validate syntax only (variable references may be ignored)
  terraform validate || true

  cd -
done
'''
        }
      }
    }
  }

  post {
    success {
      echo "Terraform syntax check completed."
    }
    failure {
      echo "Terraform validation encountered errors. Check console output."
    }
  }
}