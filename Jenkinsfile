@Library('slack-notification')
import org.gradiant.jenkins.slack.SlackNotifier

pipeline {
    agent none

    triggers {
        cron('@midnight')
    }

    environment {
        // TODO: automate
        RUDDER_VERSION = "7.0"
    }

    stages {
        stage('Tests') {
            parallel {
                stage('relayd-man') {
                    agent { 
                        dockerfile { 
                            filename 'ci/asciidoctor.Dockerfile'
                            additionalBuildArgs  "--build-arg USER_ID=${env.JENKINS_UID}"
                        }
                    }
                    when { not { branch 'master' } }
                    steps {
                        dir('relay/sources') {
                            sh script: 'make man-source', label: 'build man page'
                        }
                    }
                }
                stage('shell') {
                    agent { 
                        dockerfile { 
                            filename 'ci/shellcheck.Dockerfile'
                        }
                    }
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
                    agent { 
                        dockerfile { 
                            filename 'ci/pylint.Dockerfile'
                        }
                    }
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
                stage('typos') {
                    agent { 
                        dockerfile { 
                            filename 'ci/typos.Dockerfile'
                            additionalBuildArgs  '--build-arg VERSION=1.0'
                        }
                    }
                    steps {
                        dir('language') {
                            sh script: 'typos', label: 'check language typos'
                        }
                        dir('webapp/sources/api-doc') {
                            sh script: 'typos', label: 'check webapp api doc typos'
                        }
                        dir('relay/sources/') {
                            sh script: 'typos --exclude "*.pem"', label: 'check relayd typos'
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
                stage('api-doc') {
                    agent { 
                        dockerfile { 
                            filename 'api-doc/Dockerfile'
                            additionalBuildArgs  "--build-arg USER_ID=${env.JENKINS_UID}"
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
                stage('rudder-pkg') {
                    agent { 
                        dockerfile { 
                            filename 'relay/sources/rudder-pkg/Dockerfile'
                            args '-v /etc/passwd:/etc/passwd:ro'
                        }
                    }
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
                stage('elm') {
                    agent {
                        dockerfile {
                            filename 'webapp/sources/rudder/rudder-web/src/main/elm/Dockerfile'
                            additionalBuildArgs "--build-arg USER_ID=${env.JENKINS_UID}"
                        }
                    }
                    steps {
                        dir('webapp/sources/rudder/rudder-web/src/main/elm') {
                            sh script: './build-app.sh', label: 'build elm apps'
                            dir('editor') {
                                sh script: 'elm-test', label: 'run technique editor tests'
                            }
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
                stage('webapp') {
                    agent {
                        dockerfile {
                            filename 'webapp/sources/Dockerfile'
                            additionalBuildArgs "--build-arg USER_ID=${env.JENKINS_UID}"
                            // we don't share elm folder as it is may break with concurrent builds
                            // set same timezone as some tests rely on it
                            // and share maven cache
                            args '-v /etc/timezone:/etc/timezone:ro -v /srv/cache/maven:/home/jenkins/.m2'
                        }
                    }
                    steps {
                        sh script: 'webapp/sources/rudder/rudder-core/src/test/resources/hooks.d/test-hooks.sh', label: "hooks tests"
                        dir('webapp/sources') {
                            sh script: 'mvn clean test --batch-mode', label: "webapp tests"
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
                stage('relayd') {
                    // we need to use a script for side container currently
                    agent { label 'docker' }
                    environment {
                        POSTGRES_PASSWORD = 'PASSWORD'
                        POSTGRES_DB       = 'rudder'
                        POSTGRES_USER     = 'rudderreports'
                    }
                    steps {
                        script {
                            docker.image('postgres:11-bullseye').withRun('-e POSTGRES_USER=${POSTGRES_USER} -e POSTGRES_PASSWORD=${POSTGRES_PASSWORD} -e POSTGRES_DB=${POSTGRES_DB}', '-c listen_addresses="*"') { c ->
                                docker.build('relayd', "-f relay/sources/relayd/Dockerfile --build-arg USER_ID=${env.JENKINS_UID} --pull .")
                                      .inside("-v /srv/cache/cargo:/usr/local/cargo/registry -v /srv/cache/sccache:/home/jenkins/.cache/sccache --link=${c.id}:postgres") {
                                    dir('relay/sources/relayd') {
                                        sh script: "PGPASSWORD=${POSTGRES_PASSWORD} psql -U ${POSTGRES_USER} -h postgres -d ${POSTGRES_DB} -a -f tools/create-database.sql", label: 'provision database'
                                        sh script: 'make check', label: 'relayd tests'
                                    }
                                }
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
                stage('language') {
                    agent {
                        dockerfile { 
                            filename 'language/Dockerfile'
                            additionalBuildArgs  "--build-arg USER_ID=${env.JENKINS_UID} --build-arg RUDDER_VER=$RUDDER_VERSION"
                            // mount cache
                            args '-v /srv/cache/cargo:/usr/local/cargo/registry -v /srv/cache/sccache:/home/jenkins/.cache/sccache'
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
                            sh script: 'make check', label: 'language tests'
                            sh script: 'make docs', label: 'language docs'
                        }
                    }
                    post {
                        always {
                            // linters results
                            recordIssues enabledForFailure: true, id: 'language', name: 'cargo language', sourceDirectory: 'rudder-lang', sourceCodeEncoding: 'UTF-8',
                                         tool: cargo(pattern: 'language/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'language', name: 'cargo language')
                            script {
                                new SlackNotifier().notifyResult("rust-team")
                            }
                        }
                    }
                }
            }
        }
        stage("Compatibility tests") {
            // Expensive tests only done daily on branches
            when { 
                allOf {
                    triggeredBy 'TimerTrigger'
                    not { changeRequest() }
                }
            }
            matrix {
                axes {
                    axis {
                        name 'JDK_VERSION'
                        values '8', '17'
                    }
                }
                stages {
                    stage('webapp') {
                        agent {
                            dockerfile {
                                filename 'webapp/sources/Dockerfile'
                                additionalBuildArgs "--build-arg USER_ID=${env.JENKINS_UID} --build-arg JDK_VERSION=${JDK_VERSION}"
                                // we don't share elm folder as it is may break with concurrent builds
                                // set same timezone as some tests rely on it
                                // and share maven cache
                                args '-v /etc/timezone:/etc/timezone:ro -v /srv/cache/maven:/home/jenkins/.m2'
                            }
                        }
                        steps {
                            dir('webapp/sources') {
                                sh script: 'mvn clean test --batch-mode', label: "webapp tests"
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
                }
            }
        }
        stage('Publish') {
            when { not { changeRequest() } }
            parallel {
                stage('relayd-man') {
                    agent { 
                        dockerfile { 
                            filename 'ci/asciidoctor.Dockerfile'
                            additionalBuildArgs "--build-arg USER_ID=${env.JENKINS_UID}"
                        }
                    }
                    when { not { branch 'master' } }
                    steps {
                        dir('relay/sources') {
                            sh script: 'make man-source', label: 'build man page'
                            withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" target/man-source/rudder-relayd.1 ${KEY_USER}@${HOST_DOCS}:/var/www-docs/man/${RUDDER_VERSION}/', label: 'man page publication'
                            }
                        }
                    }
                }
                stage('api-doc') {
                    agent { 
                        dockerfile { 
                            filename 'api-doc/Dockerfile'
                            additionalBuildArgs "--build-arg USER_ID=${env.JENKINS_UID}"
                        }
                    }
                    steps {
                        dir('api-doc') {
                            sh script: 'make', label: 'build API docs'
                            withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" target/webapp/* ${KEY_USER}@${HOST_DOCS}:/var/www-docs/api/v/', label: 'publish webapp API docs'
                                sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" target/relay/* ${KEY_USER}@${HOST_DOCS}:/var/www-docs/api/relay/v/', label: 'publish relay API docs'
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
                stage('api-doc-redirect') {
                    agent { 
                        dockerfile { 
                            filename 'api-doc/Dockerfile'
                            additionalBuildArgs "--build-arg USER_ID=${env.JENKINS_UID}"
                        }
                    }
                    when { branch 'master' }
                    steps { 
                        withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                            writeFile file: 'htaccess', text: redirectApi()
                            sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" htaccess ${KEY_USER}@${HOST_DOCS}:/var/www-docs/api/.htaccess', label: "publish redirect"
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
                stage('webapp') {
                    agent {
                        dockerfile {
                            filename 'webapp/sources/Dockerfile'
                            additionalBuildArgs "--build-arg USER_ID=${env.JENKINS_UID}"
                            // we don't share elm folder as it is may break with concurrent builds
                            // set same timezone as some tests rely on it
                            // and share maven cache
                            args '-v /etc/timezone:/etc/timezone:ro -v /srv/cache/maven:/home/jenkins/.m2'
                        }
                    }
                    steps {
                        dir('webapp/sources') {
                            withMaven(globalMavenSettingsConfig: "1bfa2e1a-afda-4cb4-8568-236c44b94dbf",
                                      // don't archive jars
                                      options: [artifactsPublisher(disabled: true)]
                            ) {
                                // we need to use $MVN_COMMAND to get the settings file path
                                sh script: '$MVN_CMD -Dmaven.test.skip=true --update-snapshots clean package deploy', label: "webapp deploy"
                            }
                        }
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'webapp/sources/rudder/rudder-web/target/*.war'
                        }
                    }
                }
                stage('language') {
                    agent {
                        dockerfile { 
                            filename 'language/Dockerfile'
                            additionalBuildArgs "--build-arg USER_ID=${env.JENKINS_UID} --build-arg RUDDER_VER=${RUDDER_VERSION}"
                            // mount cache
                            args '-v /srv/cache/cargo:/usr/local/cargo/registry -v /srv/cache/sccache:/home/jenkins/.cache/sccache'
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
                            sh script: 'make docs', label: 'language docs'
                            withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" target/docs/ ${KEY_USER}@${HOST_DOCS}:/var/www-docs/language/${RUDDER_VERSION}', label: 'publish relay API docs'
                            }
                        }
                    }
                    post {
                        always {
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

def redirectApi() {
    def release_info = 'https://www.rudder-project.org/release-info/rudder'

    // Latest stable versions
    def latest_webapp = "0"
    def latest_relay = "0"

    // Decide which versions to redirect to
    def versions_r = httpRequest release_info+'/versions'
    versions_r.content.trim().split('\n').each {
        // These do not have the new API doc
        major = it.tokenize(".")[0].toInteger()
        if (major < 5) {
        println('Skipping old: '+it)
            return
        }
                                                
        released_r = httpRequest release_info+'/versions/'+it+'/released'
        released = released_r.content.trim() == 'True'
                                                
        webapp_r = httpRequest release_info+'/versions/'+it+'/api-version/webapp'
        webapp_v = webapp_r.content
        relay_r = httpRequest release_info+'/versions/'+it+'/api-version/relay'
        relay_v = relay_r.content

        if (released) {
            latest_webapp = webapp_v
            latest_relay = relay_v
        }
    }

    return """
    RedirectMatch ^/api/?\$                 /api/v/${latest_webapp}/
    RedirectMatch ^/api/openapi.yml\$       /api/v/${latest_webapp}/openapi.yml
    RedirectMatch ^/api/relay/?\$           /api/relay/v/${latest_relay}/
    RedirectMatch ^/api/relay/openapi.yml\$ /api/relay/v/${latest_relay}/openapi.yml
    """
}

