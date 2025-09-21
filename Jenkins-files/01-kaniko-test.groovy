Definition: "Pipeline script"
Script:

pipeline {
  agent {
    kubernetes {
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: worker
    image: alpine:3.19
    command:
      - cat
    tty: true
"""
    }
  }

  stages {
    stage('Say Hello') {
      steps {
        container('worker') {
          sh 'echo "Hello World"'
        }
      }
    }
  }
}