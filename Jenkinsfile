@Library('slack-notification')
import org.gradiant.jenkins.slack.SlackNotifier

pipeline {
    agent none

    stages {
        stage('qa-test') {
            agent { label 'script' }
            environment {
              PATH = "${env.HOME}/.local/bin:${env.PATH}"
            }
            steps {
                sh script: './qa-test', label: 'qa-test'
                sh script: './qa-test --typos', label: 'check typos'
                sh script: './qa-test --quick', label: 'check typos'
            }
        }
    }

    post {
        always {
            script {
                new SlackNotifier().notifyResult("shell-team")
            }
        }
    }
}
