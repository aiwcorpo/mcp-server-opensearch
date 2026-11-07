// Change requests run unreviewed code, so their stages have no credentials and no Docker socket.
pipeline {
  agent none
  stages {
    stage('verify (change request)') {
      when { changeRequest() }
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e' } }
      steps {
        sh './mvnw -B verify'
      }
      post {
        always {
          junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml'
        }
      }
    }
    stage('verify (main)') {
      when { branch 'main' }
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e' } }
      steps {
        sh './mvnw -B verify'
      }
      post {
        always {
          junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml'
        }
      }
    }
  }
}
