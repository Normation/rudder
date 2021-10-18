@Library('slack-notification')
import org.gradiant.jenkins.slack.SlackNotifier
@Library('rudder-ci-libs@master') _

pipeline {
    agent none
    triggers { cron('@daily') }
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
            post {
                always {
                    script {
                        new SlackNotifier().notifyResult("shell-team")
                    }
                }
            }

        }
        stage('ncf-tests pull-request') {
            agent { label 'rtf' }
            when { changeRequest() }
            steps {
                script {
                    deleteDir()
                    dir('ncf') {
                        checkout scm
                        sh script: 'git log -1 --pretty=%B'
                    }
                    String[] agent_versions = [
                        "ci/rudder-6.1-nightly",
                        "ci/rudder-6.2-nightly",
                        "ci/rudder-7.0-nightly"
                    ]
                    String[] systems = [
                        "debian10"
                    ]
                    String ncf_path = "${workspace}/ncf"
                    testNcfLocal(agent_versions, systems, ncf_path)
                }
            }
        }
        stage('ncf-tests branch') {
            agent { label 'rtf' }
            when { not { changeRequest() } }
            steps {
                script {
                    deleteDir()
                    dir('ncf') {
                        checkout scm
                        sh script: 'git log -1 --pretty=%B'
                    }
                    String[] agent_versions = [
                        "ci/rudder-6.1-nightly",
                        "ci/rudder-6.2-nightly",
                        "ci/rudder-7.0-nightly"
                    ]
                    String[] systems = [
                        "debian10",
                        "debian9",
                        "centos7",
                        "centos8",
                        "sles12",
                        "sles15",
                        "ubuntu18_04"
                    ]
                    String ncf_path = "${workspace}/ncf"
                    testNcfLocal(agent_versions, systems, ncf_path)
                }
            }
        }
    }
}
