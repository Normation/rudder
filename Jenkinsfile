@Library('slack-notification')
import org.gradiant.jenkins.slack.SlackNotifier

pipeline {
    agent none

    environment {
        // TODO: automate
        RUDDER_VERSION = "7.0"
    }

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
                stage('python') {
                    agent { label 'script' }
                    steps {
                        sh script: './qa-test --python', label: 'python scripts lint'
                    }
                    post {
                        always {
                            script {
                                new SlackNotifier().notifyResult("shell-team")
                            }
                        }
                    }
                }
                stage('elm') {
                    agent { label 'scala' }
                    steps {
                        dir('webapp/sources/rudder/rudder-web/src/main/elm') {
                            sh script: './build-app.sh', label: 'build elm apps'
                        }
                        dir('webapp/sources/rudder/rudder-web/src/main/elm/editor') {
                            sh script: 'elm-test --compiler elm-0.19.1', label: 'run technique editor tests'
                        }
                    }
                    post {
                        always {
                            script {
                                new SlackNotifier().notifyResult("elm-team")
                            }
                        }
                    }
                }
                stage('api-doc') {
                    agent { label 'api-docs' }

                    stages {
                        stage('api-doc-test') {
                            when {
                                anyOf {
                                    branch 'master'
                                    changeRequest()
                                }
                            }
                            steps {
                                dir('webapp/sources/api-doc') {
                                    sh script: 'typos', label: 'check typos'
                                }
                                dir('relay/sources/api-doc') {
                                    sh script: 'typos', label: 'check typos'
                                }
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
                            steps {
                                dir('webapp/sources/api-doc') {
                                    sh script: 'typos', label: 'check typos'
                                }
                                dir('relay/sources/api-doc') {
                                    sh script: 'typos', label: 'check typos'
                                }
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
                            sh script: 'typos --exclude "api-doc/*" --exclude "relayd/*"', label: 'check typos'
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

                    stages {
                        stage('webapp-test') {
                            when { changeRequest() }
                            steps {
                                sh script: 'webapp/sources/rudder/rudder-core/src/test/resources/hooks.d/test-hooks.sh', label: "hooks tests"
                                dir('webapp/sources') {
                                    withMaven(maven: "latest",
                                              // don't archive jars
                                              options: [artifactsPublisher(disabled: true)]
                                    ) {
                                        sh script: 'mvn clean test -Dmaven.test.postgres=false', label: "webapp tests"
                                    }
                                }
                            }
                            post {
                                always {
                                    // collect test results
                                    junit 'webapp/sources/**/target/surefire-reports/*.xml'

                                    script {
                                        new SlackNotifier().notifyResult("scala-team")
                                    }
                                }
                            }
                        }
                        stage('webapp-publish') {
                            when { not { changeRequest() } }
                            steps {
                                sh script: 'webapp/sources/rudder/rudder-core/src/test/resources/hooks.d/test-hooks.sh', label: "hooks tests"
                                dir('webapp/sources') {
                                    withMaven(maven: "latest",
                                              globalMavenSettingsConfig: "1bfa2e1a-afda-4cb4-8568-236c44b94dbf",
                                              // don't archive jars
                                              options: [artifactsPublisher(disabled: true)]
                                    ) {
                                        sh script: 'mvn --update-snapshots clean package deploy', label: "webapp deploy"
                                    }
                                }
                            }
                            post {
                                always {
                                    // collect test results
                                    archiveArtifacts artifacts: 'webapp/sources/rudder/rudder-web/target/*.war'
                                    junit 'webapp/sources/**/target/surefire-reports/*.xml'

                                    script {
                                        new SlackNotifier().notifyResult("scala-team")
                                    }
                                }
                            }
                        }
                    }
                }
                // No parallelism inside this stage
                stage('rust') {
                    agent { label 'rust' }

                    environment {
                        PATH = "${env.HOME}/.cargo/bin:${env.PATH}"
                    }

                    // sccache server is run via systemd on the builder
                    // because of https://github.com/mozilla/sccache/blob/master/docs/Jenkins.md

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
                        stage('language-test') {
                            when { changeRequest() }
                            environment {
                                RUSTC_WRAPPER = "sccache"
                            }
                            steps {
                                dir('language') {
                                    dir('repos') {
                                        dir('ncf') {
                                            git url: 'https://github.com/normation/ncf.git'
                                        }
                                        dir('dsc') {
                                            git url: 'https://github.com/normation/rudder-agent-windows.git',
                                                credentialsId: '17ec2097-d10e-4db5-b727-91a80832d99d'
                                        }
                                    }
                                    sh script: 'typos', label: 'check typos'
                                    sh script: 'make check', label: 'language tests'
                                    sh script: 'make docs', label: 'language docs'
                                    sh script: 'make clean', label: 'relayd clean'
                                }
                            }
                            post {
                                always {
                                    // linters results
                                    recordIssues enabledForFailure: true, id: 'language-test', name: 'cargo language', sourceDirectory: 'language', sourceCodeEncoding: 'UTF-8',
                                                 tool: cargo(pattern: 'language/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'language-test', name: 'cargo language')

                                    script {
                                        new SlackNotifier().notifyResult("rust-team")
                                    }
                                }
                            }
                        }
                        stage('language-publish') {
                            when { not { changeRequest() } }
                            environment {
                                RUSTC_WRAPPER = "sccache"
                            }
                            steps {
                                dir('language') {
                                    dir('repos') {
                                        dir('ncf') {
                                            git url: 'https://github.com/normation/ncf.git'
                                        }
                                        dir('dsc') {
                                            git url: 'https://github.com/normation/rudder-agent-windows.git',
                                                credentialsId: '17ec2097-d10e-4db5-b727-91a80832d99d'
                                        }
                                    }
                                    sh script: 'typos', label: 'check typos'
                                    sh script: 'make check', label: 'language tests'
                                    sh script: 'make docs', label: 'language docs'
                                    withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                        sh script: 'rsync -avz -e "ssh -i${KEY_FILE} -p${SSH_PORT}" target/docs/ ${KEY_USER}@${HOST_DOCS}:/var/www-docs/language/${RUDDER_VERSION}', label: 'publish relay API docs'
                                    }
				    sh script: 'make clean', label: 'language clean'
                                }
                            }
                            post {
                                always {
                                    // linters results
                                    recordIssues enabledForFailure: true, id: 'language-publish', name: 'cargo language', sourceDirectory: 'language', sourceCodeEncoding: 'UTF-8',
                                                 tool: cargo(pattern: 'language/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'language-publish', name: 'cargo language')

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
                                    sh script: 'typos --exclude "*.pem"', label: 'check typos'
                                    // lock the database to avoid race conditions between parallel tests
                                    lock('test-relayd-postgresql') {
                                        sh script: 'make check', label: 'relayd tests'
                                    }
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
                    }
                }
            }
        }
    }
}
