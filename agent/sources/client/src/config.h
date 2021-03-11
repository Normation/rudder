// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#ifndef CONFIG_H
#define CONFIG_H

#include <stdbool.h>
#include <stdint.h>
#include <toml.h>

#define PROTOCOL "https"

// uuid len = 32 chars + 4 hyphen + \o
#define MAX_ID_LEN 32 + 4 + 1

#ifdef __unix__
#    define AGENT_KEY_PASSPHRASE "Cfengine passphrase"
#elif _WIN32
#    define AGENT_KEY_PASSPHRASE "Rudder-dsc passphrase"
#endif

#ifdef DEBUG
static const char POLICY_SERVER_DAT[] = "tests/config/policy_server.dat";
#else
// FIXME windows
static const char POLICY_SERVER_DAT[] = "/var/rudder/cfengine-community/policy_server.dat";
#endif

#ifdef DEBUG
static const char UUID_HIVE[] = "tests/config/uuid.hive";
#else
// FIXME windows
static const char UUID_HIVE[] = "/opt/rudder/etc/uuid.hive";
#endif

static const char DEFAULT_CONF_FILE[] = "/opt/rudder/etc/agent.conf";

// FIXME use base from config instead
static const char DEFAULT_POLICY_CONF_FILE[] = "/var/rudder/cfengine-community/inputs/agent.conf";

// Local configuration, allowing to connect
typedef struct config {
    ////////////////////////
    // come from local config

    // WARNING: Disables certificate validation, only for testing/development
    bool insecure;
    // Policy server certificate path
    char* server_cert;
    // Policy server
    char* server;
    // My id
    char* my_id;
    // Client certificate for our agent
    char* agent_cert;
    // Private key of the agent
    char* agent_key;
    // Post used for https communication
    uint16_t https_port;
    // Directory for temporary files
    char* tmp_dir;
    // Policies directory
    char* policies_dir;

    ////////////////////////
    // come from policy config

    // Proxy used for HTTPS communication
    char* proxy;
    // User configuration for HTTP communication
    // comes from the policies
    char* user;
    // Password configuration for HTTP communication
    // comes from the policies
    char* password;
} Config;

bool config_parse(const char* config_path, const char* policy_config_path, Config* config);
void config_default(Config* config);
void config_free(Config* config);

#endif /* CONFIG_H */
