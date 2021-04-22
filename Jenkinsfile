pipeline {
    agent { label 'rust && scala' }

    stages {
        stage('shell') {
            steps {
                sh './qa-test --shell'
            }
        }
        /*
        stage('rudder-pkg') {
            steps {
                sh './qa-test --rudder-pkg'
            }
        }
        stage('webapp') {
            steps {
                sh './qa-test --scala'
            }
        }*/
        stage('relayd') {
            steps {
                sh './qa-test --relayd'
            }
        }/*
        stage('language') {
            steps {
                sh './qa-test --language'
            }
        }*/
    }

    post {
        always {
            // linters results
            recordIssues enabledForFailure: true, id: relayd, failOnError: true, sourceDirectory: 'relay/sources/relayd', tool: cargo(pattern: 'relay/sources/relayd/target/cargo-clippy.log')
        }
    }
}
