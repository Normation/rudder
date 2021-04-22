pipeline {
    agent { label 'rust && scala' }

    stages {
        stage('shell') {
            steps {
                sh './qa-test --shell'
            }
        }

        stage('rudder-pkg') {
            steps {
                sh './qa-test --rudder-pkg'
            }
        }
        stage('webapp') {
            steps {
                dir('webapp/sources') {
                    sh "mvn clean install -Dmaven.test.postgres=false"
                }
            }
            post {
                always {
                    // mvn test results
                    junit 'webapp/sources/**/target/surefire-reports/*.xml'
                }
            }
        }
        stage('relayd') {
            steps {
                dir('relay/sources/relayd') {
                    sh 'make check'
                }
            }
            post {
                always {
                    // linters results
                    recordIssues enabledForFailure: true, id: 'relayd', failOnError: true, sourceDirectory: 'relay/sources/relayd', tool: cargo(pattern: 'relay/sources/relayd/target/cargo-clippy.log')
                }
            }
        }
        stage('language') {
            steps {
                dir('rudder-lang') {
                    sh 'make check'
                }
            }
            post {
                always {
                    // linters results
                    recordIssues enabledForFailure: true, id: 'language', failOnError: true, sourceDirectory: 'rudder-lang', tool: cargo(pattern: 'rudder-lang/target/cargo-clippy.log')
                }
            }
        }
    }


}
