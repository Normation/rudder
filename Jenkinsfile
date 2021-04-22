pipeline {
    agent any

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
        stage('Deploy') {
            steps {
                echo 'Deploying....'
            }
        }
    }
}
