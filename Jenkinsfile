pipeline {
    agent none

    stages {
        stage('webapp-test') {
            agent {
                dockerfile {
                    args '-v $HOME/.cargo:/usr/local/cargo'
                }
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
        }
    }
}
