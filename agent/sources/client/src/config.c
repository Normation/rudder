// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#include "config.h"
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h> // stat
#include "log.h"
#include "utils.h"

#define MAX_ERR_LEN 200

void config_default(Config* config) {
    config->insecure = false;
    config->server = NULL;
#ifdef __unix__
    config->server_cert = strdup("/var/rudder/cfengine-community/ppkeys/policy_server.cert");
    // Not used for now on unix
    config->agent_key = strdup("/var/rudder/cfengine-community/ppkeys/localhost.priv");
    // Not used for now on unix
    config->agent_cert = strdup("/opt/rudder/etc/ssl/agent.cert");
#elif _WIN32
    config->server_cert = strdup("C:\\Program Files\\Rudder\\etc\\ssl\\policy_server.cert");
    config->agent_cert = strdup("C:\\Program Files\\Rudder\\etc\\ssl\\localhost.cert");
    config->agent_key = strdup("C:\\Program Files\\Rudder\\etc\\ssl\\localhost.priv");
#endif
    config->https_port = 443;
    config->proxy = NULL;
    config->user = strdup("rudder");
    config->password = strdup("rudder");
}

void config_free(Config* config) {
    free(config->server);
    free(config->server_cert);
    free(config->proxy);
    free(config->agent_cert);
    free(config->agent_key);
    free(config->user);
    free(config->password);
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

bool file_exists(const char* path) {
    if (path == NULL) {
        return false;
    }
    struct stat buffer;
    return (stat(path, &buffer) == 0);
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

bool policy_server_read(const char* path, char** output) {
    FILE* fp = fopen(path, "r");
    if (fp == NULL) {
        error("cannot open %s: %s", path, strerror(errno));
        return false;
    }

    char buffer[255];
    char* res = fgets(buffer, sizeof(buffer), fp);
    if (res == NULL) {
        error("cannot read: %s", path, strerror(errno));
        return false;
    }
    // strip \n
    res[strcspn(res, "\n")] = 0;
    *output = strdup(res);
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

    res = read_string_value(conf, "server", true, &config->server);
    if (res == false) {
        debug("Falling back to '%s' for policy server configuration", POLICY_SERVER_DAT);
        char* output = NULL;
        res = policy_server_read(POLICY_SERVER_DAT, &output);
        if (res == false) {
            result = false;
            goto exit;
        }
        free(config->server);
        config->server = output;
    } else {
        debug("Check '%s' for policy server configuration consistency", POLICY_SERVER_DAT);
        if (file_exists(POLICY_SERVER_DAT)) {
            char* output = NULL;
            res = policy_server_read(POLICY_SERVER_DAT, &output);
            if (res == false) {
                result = false;
                goto exit;
            }
            if (strcmp(output, config->server) != 0) {
                warn("Server configured in '%s' ('%s') does not match value from '%s' ('%s')", path,
                     config->server, POLICY_SERVER_DAT, output);
            }
            free(output);
        }
    }
    read_string_value(conf, "server_cert", false, &config->server_cert);
    read_string_value(conf, "agent_cert", false, &config->agent_cert);
    read_string_value(conf, "agent_key", false, &config->agent_key);
    read_string_value(conf, "proxy", false, &config->proxy);
    read_int_value(conf, "https_port", false, &config->https_port);
    read_bool_value(conf, "insecure", false, &config->insecure);

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
    } else if (file_exists(POLICY_SERVER_DAT)) {
        debug("Falling back to '%s' for policy server configuration as '%s' does not exist",
              POLICY_SERVER_DAT, config_path);
        char* output = NULL;
        bool res = policy_server_read(POLICY_SERVER_DAT, &output);
        if (res == false) {
            return false;
        }
        free(config->server);
        config->server = output;
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
