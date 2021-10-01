pipeline {
    agent none

    stages {
        stage('webapp-test') {
            agent {
                docker { image 'openjdk:11' }
            }
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
        }
    }
}
