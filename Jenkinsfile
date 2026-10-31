// Thin wrapper: all logic is in ci/run.sh. The registry URL (MCP_MAVEN_REPOSITORY_URL) is a plain job variable.
// Change requests run unreviewed code, so their stage has no credentials and no Docker socket; ci/run.sh then reads the
// registry anonymously. Only the main branch gets the registry credentials (Jenkins credentials id mcp-maven-registry,
// username with password) and an agent with the Docker socket for the image.
pipeline {
  agent none
  stages {
    stage('verify (change request)') {
      when { changeRequest() }
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25' } }
      steps {
        sh 'ci/run.sh verify'
      }
      post {
        always {
          junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml'
        }
      }
    }
    stage('verify (main)') {
      when { branch 'main' }
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25' } }
      steps {
        withCredentials([usernamePassword(credentialsId: 'mcp-maven-registry',
            usernameVariable: 'MCP_MAVEN_REPOSITORY_USER', passwordVariable: 'MCP_MAVEN_REPOSITORY_PASSWORD')]) {
          sh 'ci/run.sh verify'
        }
        stash name: 'build', includes: 'mcp-app/target/mcp-*.jar,mcp-app/target/mcp-manifest.json'
      }
      post {
        always {
          junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml'
        }
      }
    }
    stage('image (main)') {
      when { branch 'main' }
      agent { docker { image 'docker:27-cli'; args '-v /var/run/docker.sock:/var/run/docker.sock -u root' } }
      steps {
        sh 'apk add --no-cache bash git'
        unstash 'build'
        sh 'ci/run.sh image'
      }
    }
  }
}
