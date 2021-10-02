pipeline {
    agent none

    stages {
        stage('webapp-test') {
            agent {
                dockerfile true
                //docker { 
                    //image 'rust:1.55.0-bullseye'
                    //args '-v $HOME/.m2:/root/.m2'
                //}
            }
            steps {
                dir('relay/sources/relayd/') {
                    sh script: 'cargo build'
                }
            }
        }
    }
}
