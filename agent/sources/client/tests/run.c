#include <assert.h>
#include <stdbool.h>
#include <string.h>
#ifdef __unix__
#    include <unistd.h> // for isatty()
#endif
#include "cli.h"
#include "config.h"
#include "log.h"

// Very simple test framework
// Abort at first error
// output format losely based on rust tests

int nb_tests = 0;
bool color = false;

#define test(name, function) \
    do { \
        printf("test %s ... ", name); \
        function(); \
        nb_tests += 1; \
        printf("%s\n", green_ok()); \
    } while (0)

const char* green_ok(void) {
    if (color) {
        return "\x1b[32mok\x1b[0m";
    } else {
        return "ok";
    }
}

void test_logging(void) {
    // log_set_level(LOG_TRACE);
    error("This is an error");
    warn("This is the %d/10 warn", 3);
    info("This is an info message");
    debug("This is a debug message");
    trace("This is low level message");
}

void test_config_complete(void) {
    Config config;
    config_default(&config);

    bool res = config_parse("tests/config/complete.toml", "tests/config/policy.toml", &config);
    assert(res == true);
    assert(strcmp(config.server, "rudder") == 0);
    assert(strcmp(config.server_pubkey_hash, "sha256//ZE9q37dB6Nq+ZJz1cdrfdt+qPL+Xk8sKkLDMTp4QemY=") == 0);
    assert(strcmp(config.agent_cert, "tests/certs/agent.cert") == 0);
    assert(strcmp(config.agent_key, "tests/certs/agent.priv") == 0);
    assert(strcmp(config.proxy, "https://proxy.example.com") == 0);
    assert(strcmp(config.user, "rudder") == 0);
    assert(strcmp(config.password, "s8hOkUYiQJ54KbefibxM") == 0);
    assert(strcmp(config.tmp_dir, "/tmp") == 0);
    assert(strcmp(config.policies_dir, "/tmp/policies") == 0);
    assert(strcmp(config.my_id, "root") == 0);

    assert(config.https_port == 8443);
    assert(config.insecure == true);
    config_free(&config);
}

void test_config_minimal(void) {
    Config config;
    config_default(&config);

    bool res = config_parse("tests/config/minimal.toml", "tests/config/not_there.toml", &config);
    assert(res == true);
    assert(strcmp(config.server, "rudder") == 0);
    assert(strcmp(config.my_id, "e745a140-40bc-4b86-b6dc-084488fc906b") == 0);
    assert(config.insecure == false);
    config_free(&config);
}

void test_config_empty(void) {
    Config config;
    config_default(&config);
    // file exists but no server in it, should read policy_server.dat
    bool res = config_parse("tests/config/empty.toml", "tests/config/empty.toml", &config);
    assert(res == true);
    assert(strcmp(config.server, "127.0.0.1") == 0);
    assert(strcmp(config.my_id, "e745a140-40bc-4b86-b6dc-084488fc906b") == 0);
    config_free(&config);
}

void test_config_absent(void) {
    Config config;
    config_default(&config);
    // will read policy_server.dat
    bool res = config_parse("tests/config/not_there.toml", "tests/config/policy.toml", &config);
    assert(res == true);
    assert(strcmp(config.server, "127.0.0.1") == 0);
    assert(strcmp(config.my_id, "e745a140-40bc-4b86-b6dc-084488fc906b") == 0);
    config_free(&config);
}

/// INTEGRATION TESTS

void test_get_server_id(void) {
    const char* args[4] = { "rudder_client", "get_server_id", "-c", "tests/config/test.toml" };
    start(4, args);
    assert(strcmp(output_get(), "root") == 0);
}

/// MAIN

int main(int argc, char* argv[]) {
#ifdef __unix__
    if (isatty(STDOUT_FILENO) == 1) {
        color = true;
    }
#endif
    log_set_level(LOG_NONE);

    printf("\nrunning tests\n");

    test("config::complete", test_config_complete);
    test("config::minimal", test_config_minimal);
    test("config::empty", test_config_empty);
    test("config::absent", test_config_absent);
    test("logging", test_logging);
    test("command::get_server_id", test_get_server_id);

    printf("\ntest result: %s. %d passed.\n", green_ok(), nb_tests);
}
