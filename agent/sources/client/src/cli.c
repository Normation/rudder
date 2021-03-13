// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#include "cli.h"
#include <stdio.h>
#include <string.h>
#include "config.h"
#include "http.h"
#include "log.h"
#ifdef __unix__
#    include <unistd.h> // for isatty()
#endif
#include <curl/curl.h>
#include "argtable3.h"

#define REG_EXTENDED 1
#define REG_ICASE (REG_EXTENDED << 1)

void help(const char* progname, void* get_server_id, void* upload_report, void* upload_inventory,
          void* argtable_no_action) {
    printf("Client for Rudder's communication protocol\n\n");
    printf("USAGE:\n");
    printf("       %s", progname);
    arg_print_syntax(stdout, get_server_id, "\n");
    printf("       %s", progname);
    arg_print_syntax(stdout, upload_report, "\n");
    printf("       %s", progname);
    arg_print_syntax(stdout, upload_inventory, "\n");
    printf("       %s", progname);
    arg_print_syntax(stdout, argtable_no_action, "\n");
    printf("\n");
    printf("OPTIONS:\n");
    printf("  global options:\n");
    arg_print_glossary(stdout, argtable_no_action, "      %-20s %s\n");
    printf("\n  upload_report options:\n");
    arg_print_glossary(stdout, upload_report, "      %-20s %s\n");
    printf("\n  upload_inventory options:\n");
    arg_print_glossary(stdout, upload_inventory, "      %-20s %s\n");
}

void version(const char* progname) {
    printf("%s %s\n", progname, PROG_VERSION);
}

// entry point called by main
int start(int argc, const char* argv[]) {
    // Logging configuration
#ifdef __unix__
    if (isatty(STDOUT_FILENO) == 1) {
        log_set_color(true);
    }
#else
    // TODO the same for windows
#endif
    log_set_level(LOG_INFO);

    // config file
    Config config = { 0 };
    config_default(&config);

    /////////////////////////////////////////
    // define cli arguments
    /////////////////////////////////////////

    // The goal here is to provide an interface abstracting the network part
    // It should use business names, and hide the communication complexity.

    // Adding new subcommands is quite inconvenient

    // common options
    // --config local_config_file --policy_config policy_config_file

    // get_server_id [-v]
    struct arg_rex* get_server_id_cmd =
        arg_rex1(NULL, NULL, "get_server_id", NULL, REG_ICASE, NULL);
    struct arg_lit* get_server_id_verbose = arg_lit0("v", "verbose", "verbose output");
    struct arg_str* get_server_id_config = arg_str0("c", "config", "<file>", "configuration file");
    struct arg_end* get_server_id_end = arg_end(20);
    void* get_server_id[] = { get_server_id_cmd, get_server_id_verbose, get_server_id_config,
                              get_server_id_end };
    int get_server_id_errors;

    *get_server_id_config->sval = DEFAULT_CONF_FILE;

    // upload_report [-v] <file>
    struct arg_rex* upload_report_cmd =
        arg_rex1(NULL, NULL, "upload_report", NULL, REG_ICASE, NULL);
    struct arg_lit* upload_report_verbose = arg_lit0("v", "verbose", "verbose output");
    struct arg_str* upload_report_config = arg_str0("c", "config", "<file>", "configuration file");
    struct arg_file* upload_report_file = arg_file1(NULL, NULL, NULL, NULL);
    struct arg_end* upload_report_end = arg_end(20);
    void* upload_report[] = { upload_report_cmd, upload_report_verbose, upload_report_config,
                              upload_report_file, upload_report_end };
    int upload_report_errors;

    *upload_report_config->sval = DEFAULT_CONF_FILE;

    // upload_inventory [-v] [--new] <file>
    struct arg_rex* upload_inventory_cmd =
        arg_rex1(NULL, NULL, "upload_inventory", NULL, REG_ICASE, NULL);
    struct arg_lit* upload_inventory_verbose = arg_lit0("v", "verbose", "verbose output");
    struct arg_str* upload_inventory_config =
        arg_str0("c", "config", "<file>", "configuration file");
    struct arg_lit* upload_inventory_new = arg_lit0("n", "new", "inventory for a new node");
    struct arg_file* upload_inventory_file = arg_file1(NULL, NULL, NULL, NULL);
    struct arg_end* upload_inventory_end = arg_end(20);
    void* upload_inventory[] = { upload_inventory_cmd,    upload_inventory_verbose,
                                 upload_inventory_config, upload_inventory_new,
                                 upload_inventory_file,   upload_inventory_end };
    int upload_inventory_errors;

    *upload_inventory_config->sval = DEFAULT_CONF_FILE;

    // handles tmp copy and replacement
    // update_policies [-v]
    // FIXME ifdef windows

    // no action: [--help] [--version]
    struct arg_lit* no_action_help = arg_lit0("h", "help", "print this help and exit");
    struct arg_lit* no_action_version =
        arg_lit0("V", "version", "print version information and exit");
    struct arg_end* no_action_end = arg_end(20);
    void* no_action[] = { no_action_help, no_action_version, no_action_end };
    int no_action_errors;

    const char* progname = PROG_NAME;
    int exitcode = EXIT_SUCCESS;

    /* verify all argtable[] entries were allocated successfully */
    if (arg_nullcheck(get_server_id) != 0 || arg_nullcheck(upload_report) != 0
        || arg_nullcheck(upload_inventory) != 0 || arg_nullcheck(no_action) != 0) {
        /* NULL entries were detected, some allocations must have failed */
        printf("%s: insufficient memory\n", progname);
        exitcode = 1;
        goto exit;
    }

    /////////////////////////////////////////
    // try the different argument parsers
    /////////////////////////////////////////

    get_server_id_errors = arg_parse(argc, (char**) argv, get_server_id);
    upload_report_errors = arg_parse(argc, (char**) argv, upload_report);
    upload_inventory_errors = arg_parse(argc, (char**) argv, upload_inventory);
    no_action_errors = arg_parse(argc, (char**) argv, no_action);

    // help and version are special and treated first
    if (no_action_errors == 0) {
        if (no_action_help->count > 0) {
            help(progname, get_server_id, upload_report, upload_inventory, no_action);
        } else if (no_action_version->count > 0) {
            version(progname);
        } else {
            help(progname, get_server_id, upload_report, upload_inventory, no_action);
            exitcode = EXIT_FAILURE;
        }
        goto exit;
    }

    // Stupid but well...
    bool verbose = false;
    const char* config_file = NULL;
    const char* policy_config_file = NULL;

    if (get_server_id_errors == 0) {
        verbose = get_server_id_verbose->count > 0;
        config_file = *get_server_id_config->sval;
    } else if (upload_report_errors == 0) {
        verbose = upload_report_verbose->count > 0;
        config_file = *upload_report_config->sval;
    } else if (upload_inventory_errors == 0) {
        verbose = upload_inventory_verbose->count > 0;
        config_file = *upload_inventory_config->sval;
    } else {
        /* We get here if the command line matched none of the possible syntaxes */
        if (get_server_id_cmd->count > 0) {
            arg_print_errors(stdout, get_server_id_end, progname);
            printf("usage: %s ", progname);
            arg_print_syntax(stdout, get_server_id, "\n");
        } else if (upload_report_cmd->count > 0) {
            arg_print_errors(stdout, upload_report_end, progname);
            printf("usage: %s ", progname);
            arg_print_syntax(stdout, upload_report, "\n");
        } else if (upload_inventory_cmd->count > 0) {
            arg_print_errors(stdout, upload_inventory_end, progname);
            printf("usage: %s ", progname);
            arg_print_syntax(stdout, upload_inventory, "\n");
        } else {
            help(progname, get_server_id, upload_report, upload_inventory, no_action);
        }
        exitcode = EXIT_FAILURE;
        goto exit;
    }

    if (verbose) {
        log_set_level(LOG_DEBUG);
    }

    // Now we'll need config file
    debug("Parsing configuration file '%s'", config_file);
    bool res = config_parse(config_file, policy_config_file, &config);
    if (res == false) {
        error("Invalid configuration file, aborting");
        exitcode = EXIT_FAILURE;
        goto exit;
    }

    ///////////////
    // get curl ready

    res = curl_global_init(CURL_GLOBAL_DEFAULT);
    if (res != 0) {
        error("Could not initialize curl");
        exitcode = EXIT_FAILURE;
        goto exit;
    }

    /////////////////////////////////////////
    // make actions
    /////////////////////////////////////////

    if (get_server_id_errors == 0) {
        char* node_id = NULL;
        exitcode = get_id(config, verbose, &node_id);
        if (exitcode == CURLE_OK) {
            output(node_id);
            free(node_id);
        }
    } else if (upload_report_errors == 0) {
        exitcode = upload_file(config, verbose, *upload_report_file->filename, UploadReport, false);
    } else if (upload_inventory_errors == 0) {
        bool is_new = upload_inventory_new->count > 0;
        exitcode =
            upload_file(config, verbose, *upload_report_file->filename, UploadInventory, is_new);
    }

exit:
    debug("Freeing memory");
    arg_freetable(get_server_id, sizeof(get_server_id) / sizeof(get_server_id[0]));
    arg_freetable(upload_inventory, sizeof(upload_inventory) / sizeof(upload_inventory[0]));
    arg_freetable(upload_report, sizeof(upload_report) / sizeof(upload_report[0]));
    arg_freetable(no_action, sizeof(no_action) / sizeof(no_action[0]));
    config_free(&config);
    curl_global_cleanup();
    return exitcode;
}
