pipeline {
    agent none

    stages {
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
                }
            }
        }
        stage('api-doc') {
            agent { label 'docs' }
            steps {
                dir('api-doc') {
                    sh script: 'make', label: 'build API docs'
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'api-doc/target/*/*/*.html'
                }
                // TODO publish if on main branch
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
                }
            }
        }
        stage('webapp') {
            agent { label 'scala' }
            steps {
                dir('webapp/sources') {
                    withMaven() {
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
                }
            }
        }
        stage('relayd') {
            agent { label 'rust' }
            steps {
                dir('relay/sources/relayd') {
                    sh script: 'make check', label: 'relayd tests'
                }
            }
            post {
                always {
                    // linters results
                    recordIssues enabledForFailure: true, id: 'relayd', name: 'cargo relayd', failOnError: true, sourceDirectory: 'relay/sources/relayd', sourceCodeEncoding: 'UTF-8',
                                 tool: cargo(pattern: 'relay/sources/relayd/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'relayd', name: 'cargo relayd')
                }
            }
        }
        stage('language') {
            agent { label 'rust' }
            steps {
                dir('rudder-lang') {
                    sh script: 'make check', label: 'language tests'
                }
            }
            post {
                always {
                    // linters results
                    recordIssues enabledForFailure: true, id: 'language', name: 'cargo language', failOnError: true, sourceDirectory: 'rudder-lang', sourceCodeEncoding: 'UTF-8',
                                 tool: cargo(pattern: 'rudder-lang/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'language', name: 'cargo language')
                }
            }
        }
    }

    post {
        success {
            slackSend (color: '#00FF00', message: "SUCCESSFUL: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})")
        }


        failure {
            when { not { changeRequest() } }
            slackSend (color: '#FF0000', message: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})")
        }
    }
}
