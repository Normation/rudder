// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#include "cli.h"
#include <getopt.h>
#include <stdio.h>
#include <string.h>
#include "config.h"
#include "http.h"
#include "log.h"
#ifdef __unix__
#    include <unistd.h> // for isatty()
#endif
#include "argtable3.h"

#define REG_EXTENDED 1
#define REG_ICASE (REG_EXTENDED << 1)

void help(const char* progname, void* upload_report, void* upload_inventory,
          void* argtable_no_action) {
    printf("Client for Rudder's communcation protocol\n\n");
    printf("USAGE:\n");
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
int start(int argc, char* argv[]) {
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

    // Adding new subcommands is quite inconvenient

    // upload_report [-v] --user=<user> --password=<password> <file>
    struct arg_rex* upload_report_cmd =
        arg_rex1(NULL, NULL, "upload_report", NULL, REG_ICASE, NULL);
    struct arg_lit* upload_report_verbose = arg_lit0("v", "verbose", "verbose output");
    struct arg_str* upload_report_config = arg_str0("c", "config", "<file>", "configuration file");
    struct arg_str* upload_report_policy_config =
        arg_str0("p", "policy_config", "<file>", "policy configuration file");
    struct arg_file* upload_report_file = arg_file1(NULL, NULL, NULL, NULL);
    struct arg_end* upload_report_end = arg_end(20);
    void* upload_report[] = { upload_report_cmd,    upload_report_verbose,
                              upload_report_config, upload_report_policy_config,
                              upload_report_file,   upload_report_end };
    int upload_report_errors;

    *upload_report_config->sval = DEFAULT_CONF_FILE;
    *upload_report_config->sval = DEFAULT_POLICY_CONF_FILE;

    // upload_inventory [-v] [--new] [--user=<user>] [--password=<password>] <file>
    struct arg_rex* upload_inventory_cmd =
        arg_rex1(NULL, NULL, "upload_inventory", NULL, REG_ICASE, NULL);
    struct arg_lit* upload_inventory_verbose = arg_lit0("v", "verbose", "verbose output");
    struct arg_str* upload_inventory_config =
        arg_str0("c", "config", "<file>", "configuration file");
    struct arg_str* upload_inventory_policy_config =
        arg_str0("p", "policy_config", "<file>", "policy configuration file");
    struct arg_lit* upload_inventory_new = arg_lit0("n", "new", "inventory for a new node");
    struct arg_file* upload_inventory_file = arg_file1(NULL, NULL, NULL, NULL);
    struct arg_end* upload_inventory_end = arg_end(20);
    void* upload_inventory[] = { upload_inventory_cmd,    upload_inventory_verbose,
                                 upload_inventory_config, upload_report_policy_config,
                                 upload_inventory_new,    upload_inventory_file,
                                 upload_inventory_end };
    int upload_inventory_errors;

    *upload_inventory_config->sval = DEFAULT_CONF_FILE;
    *upload_inventory_config->sval = DEFAULT_POLICY_CONF_FILE;

    /* no action: [--help] [--version] */
    struct arg_lit* no_action_help = arg_lit0("h", "help", "print this help and exit");
    struct arg_lit* no_action_version =
        arg_lit0("V", "version", "print version information and exit");
    struct arg_end* no_action_end = arg_end(20);
    void* no_action[] = { no_action_help, no_action_version, no_action_end };
    int no_action_errors;

    const char* progname = PROG_NAME;
    int exitcode = EXIT_SUCCESS;

    /* verify all argtable[] entries were allocated successfully */
    if (arg_nullcheck(upload_report) != 0 || arg_nullcheck(upload_inventory) != 0
        || arg_nullcheck(no_action) != 0) {
        /* NULL entries were detected, some allocations must have failed */
        printf("%s: insufficient memory\n", progname);
        exitcode = 1;
        goto exit;
    }

    /////////////////////////////////////////
    // try the different argument parsers
    /////////////////////////////////////////

    upload_report_errors = arg_parse(argc, argv, upload_report);
    upload_inventory_errors = arg_parse(argc, argv, upload_inventory);
    no_action_errors = arg_parse(argc, argv, no_action);

    // help and version are special and treated first
    if (no_action_errors == 0) {
        if (no_action_help->count > 0) {
            help(progname, upload_report, upload_inventory, no_action);
        } else if (no_action_version->count > 0) {
            version(progname);
        } else {
            help(progname, upload_report, upload_inventory, no_action);
            exitcode = EXIT_FAILURE;
        }
        goto exit;
    }

    // Stupid but well...
    bool verbose = false;
    const char* config_file = NULL;
    const char* policy_config_file = NULL;

    if (upload_report_errors == 0) {
        verbose = upload_report_verbose->count > 0;
        config_file = *upload_report_config->sval;
        policy_config_file = *upload_report_policy_config->sval;
    } else if (upload_inventory_errors == 0) {
        verbose = upload_inventory_verbose->count > 0;
        config_file = *upload_inventory_config->sval;
        policy_config_file = *upload_inventory_policy_config->sval;
    } else {
        /* We get here if the command line matched none of the possible syntaxes */
        if (upload_report_cmd->count > 0) {
            arg_print_errors(stdout, upload_report_end, progname);
            printf("usage: %s ", progname);
            arg_print_syntax(stdout, upload_report, "\n");
        } else if (upload_inventory_cmd->count > 0) {
            arg_print_errors(stdout, upload_inventory_end, progname);
            printf("usage: %s ", progname);
            arg_print_syntax(stdout, upload_inventory, "\n");
        } else {
            help(progname, upload_report, upload_inventory, no_action);
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

    /////////////////////////////////////////
    // make actions
    /////////////////////////////////////////

    if (upload_report_errors == 0) {
        upload_file(config, verbose, *upload_report_file->filename, UploadReport, false);
    } else if (upload_inventory_errors == 0) {
        bool is_new = upload_inventory_new->count > 0;
        upload_file(config, verbose, *upload_report_file->filename, UploadInventory, is_new);
    }

exit:
    arg_freetable(upload_inventory, sizeof(upload_inventory) / sizeof(upload_inventory[0]));
    arg_freetable(upload_report, sizeof(upload_report) / sizeof(upload_report[0]));
    arg_freetable(no_action, sizeof(no_action) / sizeof(no_action[0]));
    config_free(&config);
    return exitcode;
}
