
def failedBuild = false
def version = "9.1"

def slackResponse = null
def changeUrl = env.CHANGE_URL

def errors = []

pipeline {
    agent none

    environment {
        RUDDER_VERSION = "${version}"
    }
    triggers {
        cron('@midnight')
    }

    options {
        buildDiscarder(logRotator(daysToKeepStr: '5', artifactDaysToKeepStr: '5'))
    }

    stages {
        stage('Tests') {
            parallel {
                stage('policies-methods') {
                    agent {
                        dockerfile {
                            label "generic-docker"
                            filename 'ci/methods.Dockerfile'
                            args '-u 0:0'
                        }
                    }
                    environment {
                        RUST_LOG = 'debug'
                    }
                    steps {
                        sh script: './qa-test --methods', label: 'methods tests'
                        dir("policies/lib") {
                            sh script: 'cargo nextest run --retries 2', label: 'methods tests'
                        }
                    }
                    post {
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - policies-methods")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error during policies-methods test - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('python-lib') {
                    agent {
                        dockerfile {
                            label "generic-docker"
                            filename 'ci/python-avocado.Dockerfile'
                            additionalBuildArgs  "--build-arg USER_ID=${env.JENKINS_UID}"
                            args '-u 0:0'
                        }
                    }
                    steps {
                        dir("policies/lib") {
                            sh script: 'avocado run --disable-sysinfo tests/quick', label: 'quick method tests'
                        }
                    }
                    post {
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - python-lib")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error during python-lib test - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('relayd-man') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'ci/asciidoctor.Dockerfile'
                            args '-u 0:0'
                        }
                    }
                    when { not { branch 'master' } }
                    steps {
                        dir('relay/sources') {
                            sh script: 'make man-source', label: 'build man page'
                        }
                    }
                    post {
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - relayd-man")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error during relayd man build - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('shell') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'ci/common.Dockerfile'
                            args '-u 0:0'
                        }
                    }

                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            sh script: './qa-test --shell', label: 'shell scripts lint'
                        }

                    }
                    post {
                        always {
                            // linters results
                            recordIssues enabledForFailure: true, failOnError: true, sourceCodeEncoding: 'UTF-8',
                                         tool: checkStyle(pattern: '.shellcheck/*.log', reportEncoding: 'UTF-8', name: 'Shell scripts')
                        }
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - shell")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error during shell tests - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('python') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'ci/common.Dockerfile'
                            args '-u 0:0'
                        }
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            sh script: './qa-test --python', label: 'python scripts lint'
                        }

                    }
                    post {
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - python")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error during python tests - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('typos') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'ci/common.Dockerfile'
                            args '-u 0:0'
                        }
                    }

                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            dir('policies') {
                                sh script: 'typos --exclude lib/tree/20_cfe_basics/cfengine --exclude lib/tree/10_ncf_internals/modules/packages --exclude "*.log"', label: 'check policies typos'
                            }
                            dir('webapp/sources/api-doc') {
                                sh script: 'typos', label: 'check webapp api doc typos'
                            }
                            dir('relay') {
                                sh script: 'typos --exclude "*.log"  --exclude "*.gpg" --exclude "*.license" --exclude "*.asc" --exclude "*.pem" --exclude "*.cert" --exclude "*.priv" --exclude "*.pub" --exclude "*.signed" --exclude "*.log" --exclude "*.json"', label: 'check relayd typos'
                            }
                        }
                    }
                    post {
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - typo")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error while checking typos - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('api-doc') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'api-doc/Dockerfile'
                            args '-u 0:0'
                        }
                    }

                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            dir('api-doc') {
                                sh script: 'make', label: 'build API docs'
                            }
                        }
                    }
                    post {
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - api-doc")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error while buiding api doc - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('webapp') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'webapp/sources/Dockerfile'
                            // we don't share elm folder as it is may break with concurrent builds
                            // set same timezone as some tests rely on it
                            // and share maven cache
                            args '-u 0:0 -v /etc/timezone:/etc/timezone:ro -v /srv/cache/elm:/root/.elm -v /srv/cache/maven:/root/.m2'
                        }
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            sh script: 'webapp/sources/rudder/rudder-core/src/test/resources/hooks.d/test-hooks.sh', label: "hooks tests"
                            dir('webapp/sources') {
                                sh script: 'mvn spotless:check --batch-mode', label: "scala format test"
                                sh script: 'mvn clean test --batch-mode', label: "webapp tests"
                            }
                        }
                    }
                    post {
                        always {
                            // collect test results
                            junit 'webapp/sources/**/target/surefire-reports/*.xml'
                        }
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - webapp")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error during webapp tests - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('relayd') {
                    // we need to use a script for side container currently
                    agent { label 'generic-docker' }
                    environment {
                        POSTGRES_PASSWORD = 'PASSWORD'
                        POSTGRES_DB       = 'rudder'
                        POSTGRES_USER     = 'rudderreports'
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            script {
                                docker.image('postgres:11-bullseye').withRun('-u 0:0 -e POSTGRES_USER=${POSTGRES_USER} -e POSTGRES_PASSWORD=${POSTGRES_PASSWORD} -e POSTGRES_DB=${POSTGRES_DB}', '-c listen_addresses="*"') { c ->
                                    docker.build('relayd', "-f relay/sources/relayd/Dockerfile --build-arg USER_ID=${env.JENKINS_UID} --pull .")
                                          .inside("-u 0:0 -v /srv/cache/cargo:/usr/local/cargo/registry -v /srv/cache/sccache:/root/.cache/sccache -v /srv/cache/cargo-vet:/root/.cache/cargo-vet --link=${c.id}:postgres") {
                                        dir('relay/sources/relayd') {
                                            sh script: "PGPASSWORD=${POSTGRES_PASSWORD} psql -U ${POSTGRES_USER} -h postgres -d ${POSTGRES_DB} -a -f tools/create-database.sql", label: 'provision database'
                                            sh script: 'make check', label: 'relayd tests'
                                        }
                                    }
                                }
                            }
                        }
                    }
                    post {
                        always {
                            // linters results
                            recordIssues enabledForFailure: true, id: 'relayd', name: 'cargo relayd', sourceCodeEncoding: 'UTF-8',
                                         tool: cargo(pattern: 'relay/sources/relayd/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'relayd', name: 'cargo relayd')
                        }
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - relayd")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error during relayd tests - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('rudder-package') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'relay/sources/rudder-package/Dockerfile'
                            // mount cache
                            args '-u 0:0 -v /srv/cache/cargo:/usr/local/cargo/registry -v /srv/cache/sccache:/root/.cache/sccache -v /srv/cache/cargo-vet:/root/.cache/cargo-vet'
                        }
                    }
                    steps {

                        script {
                            updateSlack(errors, slackResponse, version, changeUrl, false)
                        }
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            dir('relay/sources/rudder-package') {
                                sh script: 'make check', label: 'language tests'
                            }
                        }
                    }
                    post {
                        always {
                            // linters results
                            recordIssues enabledForFailure: true, id: 'rudder-package', name: 'cargo rudder-package', sourceCodeEncoding: 'UTF-8',
                                         tool: cargo(pattern: 'relay/sources/rudder-package/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'rudder-package', name: 'cargo rudder-package')
                        }
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - rudder-package")
                                //notifier.notifyResult("rust-team")
                                slackSend(channel: slackResponse.threadId, message: "Error during policies tests - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                updateSlack(errors, slackResponse, version, changeUrl, false)
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('policies') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'policies/Dockerfile'
                            // FIXME: replace by Rudder version once 9.0 builds
                            additionalBuildArgs  "--build-arg RUDDER_VER=8.3-nightly --build-arg PSANALYZER_VER=1.20.0"
                            args '-u 0:0 -v /srv/cache/cargo:/usr/local/cargo/registry -v /srv/cache/sccache:/root/.cache/sccache -v /srv/cache/cargo-vet:/root/.cache/cargo-vet'
                        }
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            dir('policies/rudderc') {
                                dir('target/repos') {
                                    dir('ncf') {
                                        git url: 'https://github.com/normation/ncf.git'
                                    }
                                    dir('dsc') {
                                        git url: 'https://github.com/normation/rudder-agent-windows.git',
                                            credentialsId: '17ec2097-d10e-4db5-b727-91a80832d99d'
                                    }
                                }
                                sh script: 'make agent-windows', label: 'install local Windows agent'
                                sh script: 'make check', label: 'rudderc tests'
                                sh script: 'make docs', label: 'rudderc docs'
                            }
                            dir('policies/rudder-report') {
                                sh script: 'make check', label: 'rudder-report tests'
                            }
                            dir('policies') {
                                sh script: 'make check', label: 'policies test'
                            }
                        }
                    }
                    post {
                        always {
                            // linters results
                            recordIssues enabledForFailure: true, id: 'policies', name: 'cargo policies', sourceCodeEncoding: 'UTF-8',
                                         tool: cargo(pattern: 'policies/target/cargo-clippy.json', reportEncoding: 'UTF-8', id: 'rudderc', name: 'cargo language')
                        }
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Tests - policies")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error during policies tests - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
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
                        // The rationale here is:
                        // * all base test already run on the lowest supported (LTS) version
                        // * add specific compatibility tests for other supported LTS + latest release
                        values '21', '22'
                    }
                }
                stages {
                    stage('webapp') {
                        agent {
                            dockerfile {
                                label 'generic-docker'
                                filename 'webapp/sources/Dockerfile'
                                additionalBuildArgs "--build-arg JDK_VERSION=${JDK_VERSION}"
                                // we don't share elm folder as it is may break with concurrent builds
                                // set same timezone as some tests rely on it
                                // and share maven cache
                                args '-u 0:0 -v /etc/timezone:/etc/timezone:ro -v /srv/cache/elm:/root/.elm -v /srv/cache/maven:/root/.m2'
                            }
                        }
                        steps {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                                dir('webapp/sources') {
                                    sh script: 'mvn clean test --batch-mode', label: "webapp tests"
                                }
                            }
                        }
                        post {
                            always {
                                // collect test results
                                junit 'webapp/sources/**/target/surefire-reports/*.xml'
                            }
                            failure {
                                script {
                                    failedBuild = true
                                    errors.add("Tests - compatibility JDK ${JDK_VERSION}")
                                    slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                    slackSend(channel: slackResponse.threadId, message: "Error during compatibility JDK ${JDK_VERSION} tests - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                                }
                            }
                            cleanup {
                                script {
                                    cleanWs(deleteDirs: true, notFailBuild: true)
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
                            label 'generic-docker'
                            filename 'ci/asciidoctor.Dockerfile'
                            args '-u 0:0'
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
                    post {
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Publish - relayd-man")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error while publishing relayd man pages - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('api-doc') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'api-doc/Dockerfile'
                            args '-u 0:0'
                        }
                    }

                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            dir('api-doc') {
                                sh script: 'make', label: 'build API docs'
                                withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                    sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" target/webapp/* ${KEY_USER}@${HOST_DOCS}:/var/www-docs/api/v/', label: 'publish webapp API docs'
                                    sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" target/relay/* ${KEY_USER}@${HOST_DOCS}:/var/www-docs/api/relay/v/', label: 'publish relay API docs'
                                }
                            }
                        }
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'api-doc/target/*/*/*.html'
                        }
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Publish - api-doc")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error while publishing api docs - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('api-doc-redirect') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'api-doc/Dockerfile'
                            args '-u 0:0'
                        }
                    }
                    when { branch 'master' }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                writeFile file: 'htaccess', text: redirectApi()
                                sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" htaccess ${KEY_USER}@${HOST_DOCS}:/var/www-docs/api/.htaccess', label: "publish redirect"
                            }
                        }
                    }
                    post {
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Publish - api-doc-redirect")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error while building api doc redirect - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('webapp') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'webapp/sources/Dockerfile'
                            // we don't share elm folder as it is may break with concurrent builds
                            // set same timezone as some tests rely on it
                            // and share maven cache
                            args '-u 0:0 -v /etc/timezone:/etc/timezone:ro -v /srv/cache/elm:/root/.elm -v /srv/cache/maven:/root/.m2'
                        }
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            dir('webapp/sources') {
                                withMaven(globalMavenSettingsConfig: "1bfa2e1a-afda-4cb4-8568-236c44b94dbf",
                                          // don't archive jars
                                          options: [artifactsPublisher(disabled: true)]
                                ) {
                                    // we need to use $MVN_COMMAND to get the settings file path
                                    // we no longer need to execute tests
                                    sh script: '$MVN_CMD -DskipTests --update-snapshots clean package deploy', label: "webapp deploy"
                                }
                            }
                        }
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'webapp/sources/rudder/rudder-web/target/*.war'
                        }
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Publish - webapp")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error while publishing webapp - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
                stage('policies') {
                    agent {
                        dockerfile {
                            label 'generic-docker'
                            filename 'policies/Dockerfile'
                            additionalBuildArgs  "--build-arg RUDDER_VER=8.3-nightly"
                            // mount cache
                            args '-u 0:0 -v /srv/cache/cargo:/usr/local/cargo/registry -v /srv/cache/sccache:/root/.cache/sccache'
                        }
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            dir('policies/rudderc') {
                                dir('target/repos') {
                                    dir('ncf') {
                                        git url: 'https://github.com/normation/ncf.git'
                                    }
                                    dir('dsc') {
                                        git url: 'https://github.com/normation/rudder-agent-windows.git',
                                            credentialsId: '17ec2097-d10e-4db5-b727-91a80832d99d'
                                    }
                                }
                                sh script: 'make docs', label: 'policies lib doc'
                                withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                    sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" target/doc/book/ ${KEY_USER}@${HOST_DOCS}:/var/www-docs/techniques/${RUDDER_VERSION}', label: 'publish techniques docs'
                                }
                                sh script: 'RUDDERC_VERSION="${RUDDER_VERSION}-${GIT_COMMIT}" make static', label: 'public binary'
                                withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                    sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" ../../target/release/rudderc packager@${HOST_REPO}:/var/www/repos/tools/rudderc/${RUDDER_VERSION}/rudderc-linux-x86_64', label: 'publish rudderc'
                                }
                            }
                            dir('policies/rudder-report') {
                                sh script: 'make static', label: 'public binary'
                                withCredentials([sshUserPrivateKey(credentialsId: 'f15029d3-ef1d-4642-be7d-362bf7141e63', keyFileVariable: 'KEY_FILE', passphraseVariable: '', usernameVariable: 'KEY_USER')]) {
                                    sh script: 'rsync -avz -e "ssh -o StrictHostKeyChecking=no -i${KEY_FILE} -p${SSH_PORT}" ../../target/release/rudder-report packager@${HOST_REPO}:/var/www/repos/tools/rudder-report/rudder-report-x86_64', label: 'publish rudder-report'
                                }
                            }
                        }
                    }
                    post {
                        failure {
                            script {
                                failedBuild = true
                                errors.add("Publish - policies")
                                slackResponse = updateSlack(errors, slackResponse, version, changeUrl, false)
                                slackSend(channel: slackResponse.threadId, message: "Error while publishing policies - <${currentBuild.absoluteUrl}|Link>", color: "#CC3421")
                            }
                        }
                        cleanup {
                            script {
                                cleanWs(deleteDirs: true, notFailBuild: true)
                            }
                        }
                    }
                }
            }
        }
        stage('End') {
            steps {
                script {
                    updateSlack(errors, slackResponse, version, changeUrl, true)
                    if (failedBuild) {
                        error 'End of build'
                    } else {
                        echo 'End of build'
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




def updateSlack(errors, slackResponse, version, changeUrl, isEnded) {
  def msg ="*${version} - rudder repo* - <"+currentBuild.absoluteUrl+"|Link>"

  if (changeUrl == null) {

      def fixed = currentBuild.resultIsBetterOrEqualTo("SUCCESS") && currentBuild.previousBuild.resultIsWorseOrEqualTo("UNSTABLE")
      if (errors.isEmpty() && isEnded && fixed) {
        msg +=  " => Build fixed! :white_check_mark:"
        def color = "good"
        slackSend(channel: "ci", message: msg, color: color)
      }


      if (! errors.isEmpty()) {
          msg += "\n*Errors* :x: ("+errors.size()+")\n  • " + errors.join("\n  • ")
          def color = "#CC3421"
          if (slackResponse == null) {
            slackResponse = slackSend(channel: "ci", message: msg, color: color)
          }
          slackSend(channel: slackResponse.channelId, message: msg, timestamp: slackResponse.ts, color: color)
      }
      return slackResponse
  }
}
