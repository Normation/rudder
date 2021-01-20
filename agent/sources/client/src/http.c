// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#include "http.h"
#include <curl/curl.h>
#include <stdlib.h>
#include <string.h>
#include "argtable3.h"
#include "log.h"

// Max hostname length is 253, plus path and scheme
#define MAX_URL_LEN (255 + 300)

#define CURL_CHECK(ret) \
    if ((ret) != CURLE_OK) { \
        error("curl_easy_setopt() failed: %s", curl_easy_strerror(ret)); \
        return (EXIT_FAILURE); \
    };

#define SNPRINTF_CHECK(length_needed, size) \
    if ((length_needed) < 0 || (unsigned) (length_needed) >= (size)) { \
        error("URL buffer too small"); \
        return (EXIT_FAILURE); \
    }

// Apply options common to all calls we make
int common_options(CURL* curl, bool verbose, const Config config) {
    CURLcode ret = 0;

    curl = curl_easy_init();
    if (!curl) {
        error("curl_easy_init() failed");
        exit(EXIT_FAILURE);
    }

    // Enforce TLS 1.2+
    debug("Enforcing TLS 1.2+");
    ret = curl_easy_setopt(curl, CURLOPT_SSLVERSION, CURL_SSLVERSION_TLSv1_2);
    CURL_CHECK(ret);

    // Follow redirections
    ret = curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    CURL_CHECK(ret);

    if (config.proxy == NULL) {
        // Enforce no proxy if not configured
        // to ignore env vars
        ret = curl_easy_setopt(curl, CURLOPT_PROXY, "");
    } else {
        debug("Enabling proxy server %s", config.proxy);
        ret = curl_easy_setopt(curl, CURLOPT_PROXY, config.proxy);
    }
    CURL_CHECK(ret);

    // Specify server cert path. We use it directly as CA.
    debug("Setting server certificate '%s'", config.server_cert);
    ret = curl_easy_setopt(curl, CURLOPT_CAINFO, config.server_cert);
    CURL_CHECK(ret);

    // Do not validate hostname. We can do it as we check certificate is identical
    // to the one we know.
    ret = curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    CURL_CHECK(ret);

    // Skip all verifications
    if (config.insecure) {
        warn("Skiping certificate validation as configured");
        ret = curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
        CURL_CHECK(ret);
    }

    if (verbose) {
        ret = curl_easy_setopt(curl, CURLOPT_VERBOSE, 1L);
        CURL_CHECK(ret);
    }

    return EXIT_SUCCESS;
}

// Make the call
int curl_call(CURL* curl) {
    CURLcode ret = 0;
    char err_buf[CURL_ERROR_SIZE];

    // Get human-readable error messages
    ret = curl_easy_setopt(curl, CURLOPT_ERRORBUFFER, err_buf);
    CURL_CHECK(ret);

    // Make the actual request
    err_buf[0] = 0;
    ret = curl_easy_perform(curl);
    if (ret != CURLE_OK) {
        // from https://curl.se/libcurl/c/CURLOPT_ERRORBUFFER.html example
        size_t len = strlen(err_buf);
        if (len) {
            error("curl: (%d) %s%s", ret, err_buf, ((err_buf[len - 1] != '\n') ? "\n" : ""));
        } else {
            error("curl: (%d) %s\n", ret, curl_easy_strerror(ret));
        }
    }

    // Cleanup
    curl_easy_cleanup(curl);

    return (int) ret;
}

// Make the upload
int upload_file(Config config, bool verbose, const char* file, UploadType type, bool new) {
    CURLcode ret = 0;
    CURL* curl = NULL;

    common_options(curl, verbose, config);

    ret = curl_easy_setopt(curl, CURLOPT_USERNAME, config.user);
    CURL_CHECK(ret);
    ret = curl_easy_setopt(curl, CURLOPT_PASSWORD, config.password);
    CURL_CHECK(ret);

    const char* endpoint = NULL;
    switch (type) {
        case UploadReport:
            endpoint = "/reports/";
            break;

        case UploadInventory:
            if (new) {
                endpoint = "/inventories/";
            } else {
                endpoint = "/inventory-updates/";
            }
            break;

        default:
            abort();
            break;
    }

    // Max hostname length is 253, plus path and scheme
    char url[MAX_URL_LEN];
    int length_needed = snprintf(url, sizeof(url), "https://%s/%s", config.server, endpoint);
    SNPRINTF_CHECK(length_needed, sizeof(url));
    // URL to use
    ret = curl_easy_setopt(curl, CURLOPT_URL, url);
    CURL_CHECK(ret);

    return curl_call(curl);
}

int get_id(Config config, bool verbose) {
    CURLcode ret = 0;
    CURL* curl = NULL;

    common_options(curl, verbose, config);

    char url[MAX_URL_LEN];
    int length_needed = snprintf(url, sizeof(url), "https://%s/uuid", config.server);
    SNPRINTF_CHECK(length_needed, sizeof(url));
    // URL to use
    ret = curl_easy_setopt(curl, CURLOPT_URL, url);
    CURL_CHECK(ret);

    return curl_call(curl);
}

int shared_files_head(Config config, bool verbose, const char* target_id, const char* my_id,
                      const char* file_id, const char* hash) {
    CURLcode ret = 0;
    CURL* curl = NULL;

    common_options(curl, verbose, config);

    // Make a HEAD request
    ret = curl_easy_setopt(curl, CURLOPT_NOBODY, 1L);
    CURL_CHECK(ret);

    char url[MAX_URL_LEN];
    int length_needed =
        snprintf(url, sizeof(url), "https://%s/rudder/relay-api/shared-files/%s/%s/%s?hash=%s",
                 config.server, target_id, my_id, file_id, hash);
    SNPRINTF_CHECK(length_needed, sizeof(url));
    // URL to use
    ret = curl_easy_setopt(curl, CURLOPT_URL, url);
    CURL_CHECK(ret);

    return curl_call(curl);
}

int shared_files_put(const Config config, bool verbose, const char* target_id, const char* my_id,
                     const char* file_id, char* ttl) {
    CURLcode ret = 0;
    CURL* curl = NULL;

    common_options(curl, verbose, config);

    // Make a PUT request
    ret = curl_easy_setopt(curl, CURLOPT_UPLOAD, 1L);
    CURL_CHECK(ret);

    // --request PUT
    // --header 'Content-Type: application/octet-stream' --data-binary @-
    // echo | cat ${file_path}.sign - ${file_path}

    char url[MAX_URL_LEN];
    int length_needed =
        snprintf(url, sizeof(url), "https://%s/rudder/relay-api/shared-files/%s/%s/%s?ttl=%s",
                 config.server, target_id, my_id, file_id, ttl);
    SNPRINTF_CHECK(length_needed, sizeof(url));
    // URL to use
    ret = curl_easy_setopt(curl, CURLOPT_URL, url);
    CURL_CHECK(ret);

    return curl_call(curl);
}
