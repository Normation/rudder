@Library('slack-notification')
import org.gradiant.jenkins.slack.SlackNotifier

pipeline {
    agent none
    triggers { cron('@daily') }
    stages {
        stage('Tests') {
            parallel {
                stage('typos') {
                    agent {
                        dockerfile {
                            filename 'ci/typos.Dockerfile'
                            additionalBuildArgs  '--build-arg VERSION=1.0'
                        }
                    }
                    steps {
                        dir('language') {
                            sh script: 'typos', label: 'check language typos'
                        }
                    }
                    post {
                        fixed {
                            script {
                                new SlackNotifier().notifyResult("shell-team")
                            }
                        }
                        failure {
                            script {
                                new SlackNotifier().notifyResult("shell-team")
                            }
                        }

                    }
                }
                stage('python') {
                    agent {
                        dockerfile {
                            filename 'ci/python.Dockerfile'
                            additionalBuildArgs  "--build-arg USER_ID=${env.JENKINS_UID}"
                        }
                    }
                    steps {
                        sh script: './qa-test --python', label: 'python scripts lint'
                        sh script: './qa-test --quick', label: 'quick method tests'
                    }
                    post {
                        fixed {
                            script {
                                new SlackNotifier().notifyResult("shell-team")
                            }
                        }
                        failure {
                            script {
                                new SlackNotifier().notifyResult("shell-team")
                            }
                        }

                    }
                }
            }
        }
        stage('methods') {
            agent {
                dockerfile {
                    filename 'ci/methods.Dockerfile'
                    // Run tests as root
                    args  "--user 0"
                    additionalBuildArgs "--build-arg OS=ubuntu:20.04"
                }
            }
            steps {
                catchError {
                    sh script: 'PATH="/opt/rudder/bin:$PATH" make test', label: 'test methods'
                }
                // clean leftover files owned by root anyway
                sh script: 'rm -rf tests/acceptance/.succeeded', label: 'cleanup'
                sh script: 'rm -rf tests/acceptance/.failed', label: 'cleanup'
                sh script: 'rm -rf tests/acceptance/workdir', label: 'cleanup'
            }
            post {
                fixed {
                    script {
                        new SlackNotifier().notifyResult("shell-team")
                    }
                }
                failure {
                    script {
                        new SlackNotifier().notifyResult("shell-team")
                    }
                }

            }
        }
        stage("Compatibility tests") {
            // Expensive tests only done daily on branches
            when {
                allOf {
                    triggeredBy 'TimerTrigger'
                    not { changeRequest() }
                }
            }
            matrix {
                axes {
                    axis {
                        name 'OS'
                        values 'debian:11'
                    }
                }
                stages {
                    stage('methods') {
                        agent {
                            dockerfile {
                                filename 'ci/methods.Dockerfile'
                                // Run tests as root
                                args  "--user 0"
                                additionalBuildArgs "--build-arg OS=${OS}"
                            }
                        }
                        steps {
                            catchError {
                                sh script: 'PATH="/opt/rudder/bin:$PATH" make test', label: 'test methods'
                            }
                            // clean leftover files owned by root anyway
                            sh script: 'rm -rf tests/acceptance/.succeeded', label: 'cleanup'
                            sh script: 'rm -rf tests/acceptance/.failed', label: 'cleanup'
                            sh script: 'rm -rf tests/acceptance/workdir', label: 'cleanup'
                        }
                        post {
                            fixed {
                                script {
                                    new SlackNotifier().notifyResult("shell-team")
                                }
                            }
                            failure {
                                script {
                                    new SlackNotifier().notifyResult("shell-team")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
