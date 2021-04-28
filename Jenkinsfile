@Library('slack-notification')
import org.gradiant.jenkins.slack.SlackNotifier

pipeline {
    agent none

    stages {
        stage('Tests') {
            parallel {
                stage('shell') {
                    agent { label 'script' }
                    steps {
                        sh script: './qa-test --shell', label: 'shell scripts lint'
                    }
                    post {
                        always {
                            // linters results
                            recordIssues enabledForFailure: true, failOnError: true, sourceCodeEncoding: 'UTF-8',
                                         tool: checkStyle(pattern: '.shellcheck/*.log', reportEncoding: 'UTF-8', name: 'Shell scripts')

                            script {
                                new SlackNotifier().notifyResult("shell-team")
                            }
                        }
                    }
                }
                stage('api-doc') {
                    agent { label 'docs' }

                    stages {
                        stage('api-doc') {
                            when {
                                anyOf {
                                    branch 'master'
                                    changeRequest()
                                }
                            }
                            steps {
                                dir('api-doc') {
                                    sh script: 'make', label: 'build API docs'
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
                        stage('api-doc-publish') {
                            when {
                                not {
                                    anyOf {
                                        branch 'master'
                                        changeRequest()
                                    }
                                }
                            }
                            agent { label 'docs' }
                            steps {
                                dir('api-doc') {
                                    sh script: 'make', label: 'build API docs'
                                    withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                        sh script: 'rsync -avz -e "ssh -i${KEY_FILE} -p${SSH_PORT}" target/webapp/* ${KEY_USER}@${HOST_DOCS}:/var/www-docs/api/v/', label: 'publish webapp API docs'
                                        sh script: 'rsync -avz -e "ssh -i${KEY_FILE} -p${SSH_PORT}" target/relay/* ${KEY_USER}@${HOST_DOCS}:/var/www-docs/api/relay/v/', label: 'publish relay API docs'
                                    }
                                }
                            }
                            post {
                                always {
                                    archiveArtifacts artifacts: 'api-doc/target/*/*/*.html'

                                    script {
                                        new SlackNotifier().notifyResult("shell-team")
                                    }
                                }
                            }
                        }
                    }
                }
                stage('rudder-pkg') {
                    agent { label 'script' }
                    steps {
                        dir ('relay/sources') {
                            sh script: 'make check', label: 'rudder-pkg tests'
                        }
                    }
                    post {
                        always {
                            // linters results
                            recordIssues enabledForFailure: true, id: 'rudder-pkg', failOnError: true, sourceDirectory: 'relay/sources/rudder-pkg/', sourceCodeEncoding: 'UTF-8',
                                         tool: pyLint(pattern: 'relay/sources/rudder-pkg/pylint.log', reportEncoding: 'UTF-8')

                            script {
                                new SlackNotifier().notifyResult("python-team")
                            }
                        }
                    }
                }
                stage('webapp') {
                    agent { label 'scala' }
                    steps {
                        dir('webapp/sources') {
                            withMaven(options: [artifactsPublisher(disabled: true)]) {
                                sh script: 'mvn clean install -Dmaven.test.postgres=false', label: "webapp tests"
                            }
                        }
                        sh script: 'webapp/sources/rudder/rudder-core/src/test/resources/hooks.d/test-hooks.sh', label: "hooks tests"
                    }
                    post {
                        always {
                            // collect test results
                            junit 'webapp/sources/**/target/surefire-reports/*.xml'
                            archiveArtifacts artifacts: 'webapp/sources/rudder/rudder-web/target/*.war'

                            script {
                                new SlackNotifier().notifyResult("scala-team")
                            }
                        }
                    }
                }
                // No parallelism inside this stage
                stage('rust') {
                    agent { label 'rust' }

                    // sccache server is run via systemd on the builder
                    // because of https://github.com/mozilla/sccache/blob/master/docs/Jenkins.md

                    environment {
                        PATH = "${env.HOME}/.cargo/bin:${env.PATH}"
                    }

                    stages {
                        // No built-in support for Rust tooling in Jenkins, let's do it ourselves
                        stage('rust-tools') {
                            steps {
                                // System dependencies: libssl-dev pkg-config
                                sh script: 'make -f rust.makefile setup', label: "Setup build tools"
                            }

                            post {
                                always {
                                    script {
                                        new SlackNotifier().notifyResult("rust-team")
                                    }
                                }
                            }
                        }
                        stage('relayd') {
                            environment {
                                RUSTC_WRAPPER = "sccache"
                            }
                            steps {
                                // System dependencies: libpq-dev postgresql
                                dir('relay/sources/relayd') {
                                    sh script: 'make check', label: 'relayd tests'
                                }
                            }
                            post {
                                always {
                                    // linters results
                                    recordIssues enabledForFailure: true, id: 'relayd', name: 'cargo relayd', sourceDirectory: 'relay/sources/relayd', sourceCodeEncoding: 'UTF-8',
                                                 tool: cargo(pattern: 'relay/sources/relayd/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'relayd', name: 'cargo relayd')

                                    script {
                                        new SlackNotifier().notifyResult("rust-team")
                                    }
                                }

                            }
                        }
                        stage('language') {
                            environment {
                                RUSTC_WRAPPER = "sccache"
                            }
                            steps {
                                dir('rudder-lang') {
                                    sh script: 'make check', label: 'language tests'
                                }
                            }
                            post {
                                always {
                                    // linters results
                                    recordIssues enabledForFailure: true, id: 'language', name: 'cargo language', sourceDirectory: 'rudder-lang', sourceCodeEncoding: 'UTF-8',
                                                 tool: cargo(pattern: 'rudder-lang/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'language', name: 'cargo language')

                                    script {
                                        new SlackNotifier().notifyResult("rust-team")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
