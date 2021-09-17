@Library('slack-notification')
import org.gradiant.jenkins.slack.SlackNotifier
@Library('rudder-ci-libs@fda') _

node('script') {
   stage('qa-test') {
       environment {
         PATH = "${env.HOME}/.local/bin:${env.PATH}"
       }
       sh script: './qa-test', label: 'qa-test'
       sh script: './qa-test --typos', label: 'check typos'
       sh script: './qa-test --quick', label: 'check typos'
   }
}

node('rtf') {
    stage('ncf-tests') {
        deleteDir()
        dir('ncf') {
            checkout scm
            sh script: 'git log -1 --pretty=%B'
        }
        script {
            String[] versions = [
                "ci/rudder-6.1-nightly",
                "ci/rudder-6.2-nightly",
                "ci/rudder-7.0-nightly"
            ]
            String[] systems = ["debian10"]
            String ncf_path = "${workspace}/ncf"
            testNcfLocal(versions, systems, ncf_path)
        }
    }
}
