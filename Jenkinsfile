pipeline {
  agent {
    kubernetes {
      label 'kaniko'
    }
  }

  environment {
    IMAGE = "docker.io/jgowler/jenkins-test:${env.BUILD_NUMBER}"
  }

  stages {
    stage('Checkout') {
      steps {
        git branch: 'main', url: 'https://github.com/jgowler/Python-Terraform-Kubernetes-Project.git'
      }
    }

    stage('Build & Push') {
      steps {
        container('kaniko') {
          withCredentials([usernamePassword(
            credentialsId: 'dockerhub-creds',
            usernameVariable: 'DOCKER_USER',
            passwordVariable: 'DOCKER_PASS')]) {
            sh '''
              mkdir -p /kaniko/.docker
              echo "{\"auths\":{\"https://index.docker.io/v1/\":{\"username\":\"$DOCKER_USER\",\"password\":\"$DOCKER_PASS\",\"auth\":\"$(echo -n "$DOCKER_USER:$DOCKER_PASS" | base64)\"}}}" > /kaniko/.docker/config.json

              /kaniko/executor \
                --context "${WORKSPACE}" \
                --dockerfile "${WORKSPACE}/Docker-Files/Dockerfile" \
                --destination "${IMAGE}" \
                --verbosity debug
            '''
          }
        }
      }
    }
  }

  post {
    success {
      echo "✅ Image pushed: ${IMAGE}"
    }
    failure {
      echo "❌ Kaniko build failed. Check logs above for details."
    }
  }
}
