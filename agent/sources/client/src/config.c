// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#include "config.h"
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "log.h"
#include "utils.h"

#define MAX_ERR_LEN 200

void config_default(Config* config) {
    config->insecure = false;
    config->server = NULL;
    config->my_id = NULL;
#ifdef __unix__
    config->server_cert = strdup_compat("/var/rudder/cfengine-community/ppkeys/policy_server.cert");
    // Not used for now on unix
    config->agent_key = strdup_compat("/var/rudder/cfengine-community/ppkeys/localhost.priv");
    // Not used for now on unix
    config->agent_cert = strdup_compat("/opt/rudder/etc/ssl/agent.cert");
#elif _WIN32
    config->server_cert = strdup_compat("C:\\Program Files\\Rudder\\etc\\ssl\\policy_server.cert");
    config->agent_cert = strdup_compat("C:\\Program Files\\Rudder\\etc\\ssl\\localhost.cert");
    config->agent_key = strdup_compat("C:\\Program Files\\Rudder\\etc\\ssl\\localhost.priv");
#endif
    config->https_port = 443;
    config->proxy = NULL;
    config->user = strdup_compat("rudder");
    config->password = strdup_compat("rudder");

#ifdef __unix__
    config->tmp_dir = strdup_compat("/var/rudder/tmp");
#elif _WIN32
    config->tmp_dir = strdup_compat("C:\\Program Files\\Rudder\\tmp");
#endif

#ifdef __unix__
    config->policies_dir = strdup_compat("/var/rudder/cfengine-community/inputs/");
#elif _WIN32
    config->policies_dir = strdup_compat("C:\\Program Files\\Rudder\\policy\\");
#endif
}

void config_free(Config* config) {
    free(config->server);
    free(config->my_id);
    free(config->server_cert);
    free(config->proxy);
    free(config->agent_cert);
    free(config->agent_key);
    free(config->user);
    free(config->password);
    free(config->tmp_dir);
    free(config->policies_dir);
}

bool read_string_value(toml_table_t* conf, const char* const key, bool required, char** value) {
    toml_datum_t res = toml_string_in(conf, key);
    if (res.ok) {
        free(*value);
        *value = res.u.s;
        return true;
    } else {
        if (required) {
            error("missing %s parameter", key);
        } else {
            debug("no %s config, using default '%s'", key, *value ? *value : "");
        }
        return false;
    }
}

bool read_int_value(toml_table_t* conf, const char* const key, bool required, uint16_t* value) {
    toml_datum_t res = toml_int_in(conf, key);
    if (res.ok) {
        *value = res.u.i;
        return true;
    } else {
        if (required) {
            error("missing %s parameter", key);
        } else {
            debug("no %s config, using default '%d'", key, *value);
        }
        return false;
    }
}

bool read_bool_value(toml_table_t* conf, const char* const key, bool required, bool* value) {
    toml_datum_t res = toml_bool_in(conf, key);
    if (res.ok) {
        *value = res.u.b;
        return true;
    } else {
        if (required) {
            error("missing %s parameter", key);
        } else {
            debug("no %s config, using default '%s'", key, *value ? "true" : "false");
        }
        return false;
    }
}

bool parse_toml(const char* path, toml_table_t** conf) {
    FILE* fp = fopen(path, "r");
    if (fp == NULL) {
        error("cannot open %s: %s", path, strerror(errno));
        return false;
    }

    char errbuf[MAX_ERR_LEN];
    *conf = toml_parse_file(fp, errbuf, sizeof(errbuf));
    fclose(fp);
    if (conf == NULL) {
        error("cannot parse %s: %s", path, errbuf);
        return false;
    }

    return true;
}

bool fallback_in_file(const char* file, char** property, bool is_in_config) {
    bool res = true;

    if (is_in_config == false) {
        debug("Falling back to '%s' for node id configuration", file);
        char* output = NULL;
        res = read_file_content(file, &output);
        if (res == false) {
            return false;
        }
        free(*property);
        *property = output;
    } else {
        debug("Check '%s' for policy server configuration consistency", file);
        if (file_exists(file)) {
            char* output = NULL;
            res = read_file_content(file, &output);
            if (res == false) {
                return false;
            }
            if (strcmp(output, *property) != 0) {
                warn(
                    "Node id configured in configuration ('%s') does not match value from '%s' ('%s')",
                    *property, file, output);
            }
            free(output);
        }
    }

    return true;
}

// If it returns true, `config` contains the parsed config.
bool local_config_parse(const char* path, Config* config) {
    bool result = true;

    toml_table_t* conf = NULL;
    bool res = false;
    res = parse_toml(path, &conf);
    if (res == false) {
        return false;
    }

    // Special case for policy server
    res = read_string_value(conf, "server", true, &config->server);
    res = fallback_in_file(POLICY_SERVER_DAT, &config->server, res);
    if (res == false) {
        result = false;
        goto exit;
    }

    // Special case for my_id
    res = read_string_value(conf, "my_id", true, &config->my_id);
    res = fallback_in_file(UUID_HIVE, &config->my_id, res);
    if (res == false) {
        result = false;
        goto exit;
    }

    read_string_value(conf, "server_cert", false, &config->server_cert);
    read_string_value(conf, "agent_cert", false, &config->agent_cert);
    read_string_value(conf, "agent_key", false, &config->agent_key);
    read_string_value(conf, "proxy", false, &config->proxy);
    read_int_value(conf, "https_port", false, &config->https_port);
    read_bool_value(conf, "insecure", false, &config->insecure);
    read_string_value(conf, "tmp_dir", false, &config->tmp_dir);
    read_string_value(conf, "policies_dir", false, &config->policies_dir);

exit:
    toml_free(conf);
    return result;
}

bool policy_config_parse(const char* path, Config* config) {
    toml_table_t* conf = NULL;
    parse_toml(path, &conf);

    read_string_value(conf, "user", false, &config->user);
    read_string_value(conf, "password", false, &config->password);

    toml_free(conf);
    return true;
}

// If it returns true, `config` contains the parsed config.
bool config_parse(const char* config_path, const char* policy_config_path, Config* config) {
    if (file_exists(config_path)) {
        bool res = local_config_parse(config_path, config);
        if (res == false) {
            return false;
        }
    } else if (file_exists(POLICY_SERVER_DAT) && file_exists(UUID_HIVE)) {
        bool res = true;

        debug(
            "Falling back to defaults for policy server and node id configuration as '%s' does not exist",
            config_path);
        char* server = NULL;
        res = read_file_content(POLICY_SERVER_DAT, &server);
        if (res == false) {
            return false;
        }
        free(config->server);
        config->server = server;

        char* id = NULL;
        res = read_file_content(UUID_HIVE, &id);
        if (res == false) {
            return false;
        }
        free(config->my_id);
        config->my_id = id;
    } else {
        // we need the server config
        return false;
    }

    // normal if new agent, we have sensible defaults
    if (file_exists(policy_config_path)) {
        bool res = policy_config_parse(policy_config_path, config);
        if (res == false) {
            return false;
        }
    } else {
        debug("Falling back to default value for policy configuration as '%s' does not exist",
              policy_config_path);
    }

    return true;
}
