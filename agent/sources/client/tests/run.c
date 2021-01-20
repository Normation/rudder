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

    bool res = config_parse("tests/config/agent.toml", "tests/config/policy.toml", &config);
    assert(res == true);
    assert(strcmp(config.server, "rudder") == 0);
    assert(strcmp(config.server_cert, "/tmp/cert") == 0);
    assert(strcmp(config.agent_cert, "/tmp/client.cert") == 0);
    assert(strcmp(config.agent_key, "/tmp/client.key") == 0);
    assert(strcmp(config.proxy, "https://proxy.example.com") == 0);
    assert(strcmp(config.user, "rudder") == 0);
    assert(strcmp(config.password, "s8hOkUYiQJ54KbefibxM") == 0);

    assert(config.https_port == 4443);
    assert(config.insecure == true);
    config_free(&config);
}

void test_config_minimal(void) {
    Config config;
    config_default(&config);

    bool res = config_parse("tests/config/minimal.toml", "tests/config/not_there.toml", &config);
    assert(res == true);
    assert(strcmp(config.server, "rudder") == 0);
    assert(strcmp(config.server_cert, "/var/rudder/cfengine-community/ppkeys/policy_server.cert")
           == 0);
    assert(config.insecure == false);
    config_free(&config);
}

void test_config_empty(void) {
    Config config;
    config_default(&config);
    // file exists but no server in it, should read policy_server.dat
    bool res = config_parse("tests/config/empty.toml", "tests/config/empty.toml", &config);
    assert(res == true);
    printf("%s\n", config.server);
    assert(strcmp(config.server, "rudder") == 0);
    config_free(&config);
}

void test_config_absent(void) {
    Config config;
    config_default(&config);
    // will read policy_server.dat
    bool res = config_parse("tests/config/not_there.toml", "tests/config/policy.toml", &config);
    assert(res == true);
    printf("%s\n", config.server);
    assert(strcmp(config.server, "rudder") == 0);
    config_free(&config);
}

int main(int argc, char* argv[]) {
#ifdef __unix__
    if (isatty(STDOUT_FILENO) == 1) {
        color = true;
    }
#endif
    log_set_level(LOG_DEBUG);

    printf("\nrunning tests\n");

    test("config::complete", test_config_complete);
    test("config::minimal", test_config_minimal);
    test("config::empty", test_config_empty);
    test("config::absent", test_config_absent);
    test("logging", test_logging);

    printf("\ntest result: %s. %d passed.\n", green_ok(), nb_tests);
}
