pipeline {
    agent none

    stages {
        stage('shell') {
            agent { label 'script' }
            steps {
                sh './qa-test --shell'
            }
        }
        stage('rudder-pkg') {
            agent { label 'script' }
            steps {
                dir ('relay/sources') {
                    sh 'make check'
                }
            }
        }
        stage('webapp') {
            agent { label 'scala' }
            steps {
                dir('webapp/sources') {
                    withMaven(maven: 'latest') {
                        sh script: 'mvn clean install -Dmaven.test.postgres=false', label: "Webapp tests"
                    }
                }
                sh script: 'webapp/sources/rudder/rudder-core/src/test/resources/hooks.d/test-hooks.sh', label: "Hooks tests"
            }
            post {
                always {
                    // collect test results
                    junit 'webapp/sources/**/target/surefire-reports/*.xml'
                }
            }
        }
        stage('relayd') {
            agent { label 'rust' }
            steps {
                dir('relay/sources/relayd') {
                    sh 'make check'
                }
            }
            post {
                always {
                    // linters results
                    recordIssues enabledForFailure: true, id: 'relayd', failOnError: true, sourceDirectory: 'relay/sources/relayd', tool: cargo(pattern: 'relay/sources/relayd/target/cargo-clippy.json')
                }
            }
        }
        stage('language') {
            agent { label 'rust' }
            steps {
                dir('rudder-lang') {
                    sh 'make check'
                }
            }
            post {
                always {
                    // linters results
                    recordIssues enabledForFailure: true, id: 'language', failOnError: true, sourceDirectory: 'rudder-lang', tool: cargo(pattern: 'rudder-lang/target/cargo-clippy.json')
                }
            }
        }
    }


}
