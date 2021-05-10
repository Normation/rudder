@Library('slack-notification')
import org.gradiant.jenkins.slack.SlackNotifier

pipeline {
    agent none

    stages {
        stage('qa-test') {
            agent { label 'script' }
            steps {
                sh script: './qa-test', label: 'qa-test'
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
