// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#ifdef __unix__
#    define _XOPEN_SOURCE 500
#endif

#include "http.h"
#include <curl/curl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h> // stat
#include "argtable3.h"
#include "log.h"
#include "utils.h"

// Max hostname length is 253, plus path and scheme
#define MAX_URL_LEN (255 + 300)
#define MAX_PATH_LEN 255

#define CURL_CHECK(ret) \
    if ((ret) != CURLE_OK) { \
        error("curl_easy_setopt() failed: %d %s", ret, curl_easy_strerror(ret)); \
        return (ret); \
    };

// stackoverflow
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

    ret = curl_easy_setopt(curl, CURLOPT_PORT, config.https_port);
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

    return CURLE_OK;
}

int client_passwd_auth(CURL** curl, const Config config) {
    CURLcode ret = 0;

    ret = curl_easy_setopt(*curl, CURLOPT_USERNAME, config.user);
    CURL_CHECK(ret);
    ret = curl_easy_setopt(*curl, CURLOPT_PASSWORD, config.password);
    CURL_CHECK(ret);

    return CURLE_OK;
}

int client_cert_auth(CURL** curl, const Config config) {
    CURLcode ret = 0;

    ret = curl_easy_setopt(*curl, CURLOPT_SSLCERT, config.agent_cert);
    CURL_CHECK(ret);
    ret = curl_easy_setopt(*curl, CURLOPT_SSLKEY, config.agent_key);
    CURL_CHECK(ret);
    ret = curl_easy_setopt(*curl, CURLOPT_SSLKEYPASSWD, AGENT_KEY_PASSPHRASE);
    CURL_CHECK(ret);

    return CURLE_OK;
}

// Make the call
int curl_call(CURL* curl) {
    CURLcode ret = 0;
    char err_buf[CURL_ERROR_SIZE];
    printf("TOTO2\n");

    // Get human-readable error messages
    ret = curl_easy_setopt(curl, CURLOPT_ERRORBUFFER, err_buf);
    CURL_CHECK(ret);
    printf("TOTO2\n");

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

////////////////////////////////////////////////
////////////////////////////////////////////////

// Make the upload
int upload_file(Config config, bool verbose, const char* file, UploadType type, bool new) {
    CURLcode ret = 0;
    CURL* curl = NULL;

    ret = common_options(curl, verbose, config);
    CURL_CHECK(ret);
    ret = client_passwd_auth(&curl, config);
    CURL_CHECK(ret);
    ret = curl_easy_setopt(curl, CURLOPT_UPLOAD, 1L);
    CURL_CHECK(ret);

    // File to upload
    FILE* fd = NULL;
    fd = fopen(file, "rb");
    if (fd == NULL) {
        return EXIT_FAILURE;
    }
    // FIXME not enough for windows
    // https://curl.se/libcurl/c/CURLOPT_READDATA.html
    ret = curl_easy_setopt(curl, CURLOPT_READDATA, fd);
    CURL_CHECK(ret);

    // File size
    struct stat file_info;
#ifdef __unix__
    int stat_res = fstat(fileno(fd), &file_info);
#elif _WIN32
    int stat_res = fstat(_fileno(fd), &file_info);
#endif
    if (stat_res != 0) {
        return EXIT_FAILURE;
    }
    ret = curl_easy_setopt(curl, CURLOPT_INFILESIZE_LARGE, (curl_off_t) file_info.st_size);
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
    int length_needed = snprintf(url, sizeof(url), "%s://%s/%s", PROTOCOL, config.server, endpoint);
    SNPRINTF_CHECK(length_needed, sizeof(url));
    // URL to use
    ret = curl_easy_setopt(curl, CURLOPT_URL, url);
    CURL_CHECK(ret);

    return curl_call(curl);
}

int get_id(Config config, bool verbose) {
    CURLcode ret = 0;
    CURL* curl = NULL;

    ret = common_options(curl, verbose, config);
    CURL_CHECK(ret);

    printf("TOTOA\n");

    char url[MAX_URL_LEN];
    int length_needed = snprintf(url, sizeof(url), "%s://%s/uuid", PROTOCOL, config.server);
    SNPRINTF_CHECK(length_needed, sizeof(url));
    printf("TOTO URL: %s\n", url);

    // URL to use
    ret = curl_easy_setopt(curl, CURLOPT_URL, url);
    printf("TOTO41\n");

    // Capture output to allow testing

    CURL_CHECK(ret);

    printf("TOTO3\n");

    return curl_call(curl);
}

static size_t get_etag(void* ptr, size_t size, size_t nmemb, void* userdata) {
    char* etag = calloc(255, 1);
    // FIXME Is it correct?
    int r = sscanf(ptr, "ETag: %s\n", etag);
    if (r != EOF) {
        *(char**) userdata = etag;
    } else {
        free(etag);
    }
    return size * nmemb;
}

int update_policies(Config config, bool verbose) {
    CURLcode ret = 0;
    CURL* curl = NULL;

    int length_needed = 0;

    // Define paths
    char etag_file[MAX_PATH_LEN];
    length_needed = snprintf(etag_file, sizeof(etag_file), "%s%srudder.etag", config.policies_dir,
                             PATH_SEPARATOR);
    SNPRINTF_CHECK(length_needed, sizeof(etag_file));

    char zip_file[MAX_PATH_LEN];
    length_needed =
        snprintf(zip_file, sizeof(zip_file), "%s%srudder.zip", config.tmp_dir, PATH_SEPARATOR);
    SNPRINTF_CHECK(length_needed, sizeof(zip_file));

    char policies_tmp_dir[MAX_PATH_LEN];
    length_needed = snprintf(policies_tmp_dir, sizeof(policies_tmp_dir), "%s%sdsc", config.tmp_dir,
                             PATH_SEPARATOR);
    SNPRINTF_CHECK(length_needed, sizeof(policies_tmp_dir));

    char url[MAX_URL_LEN];
    length_needed = snprintf(url, sizeof(url), "%s://%s//policies/%s/rules/dsc/rudder.zip",
                             PROTOCOL, config.server, config.my_id);
    SNPRINTF_CHECK(length_needed, sizeof(url));

    // Start update

    // Start be removing previous tmp policy dir

    // Then read current etag
    char* etag = NULL;
    if (file_exists(etag_file)) {
        bool read = read_file_content(etag_file, &etag);
        if (read == false) {
            return EXIT_FAILURE;
        }
    }

    // Fetch current etag
    ret = common_options(curl, verbose, config);
    CURL_CHECK(ret);
    ret = client_cert_auth(&curl, config);
    CURL_CHECK(ret);
    // HEAD call
    ret = curl_easy_setopt(curl, CURLOPT_NOBODY, 1L);
    CURL_CHECK(ret);
    ret = curl_easy_setopt(curl, CURLOPT_URL, url);
    CURL_CHECK(ret);

    struct curl_slist* list = NULL;
    list = curl_slist_append(list, "If-None-Match: ");
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, list);
    CURL_CHECK(ret);

    ret = curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, get_etag);
    CURL_CHECK(ret);

    char* remote_etag = NULL;
    ret = curl_easy_setopt(curl, CURLOPT_HEADERDATA, &remote_etag);
    CURL_CHECK(ret);

    ret = curl_call(curl);
    CURL_CHECK(ret);

    curl_slist_free_all(list);

    bool update_needed = true;
    if (remote_etag == NULL) {
        error("Missing ETag in server response, assuming out-of-date policies");
    } else if (etag == NULL) {
        debug("No previous ETag found, updating");
    } else {
        if (strcmp(etag, remote_etag) == 0) {
            debug("Remote ETag '%s' matches latest policies, no need to update", remote_etag);
            update_needed = false;
        } else {
            debug("Remote ETag '%s' does not match latest '%s', updating", remote_etag, etag);
        }
    }
    free(remote_etag);

    if (!update_needed) {
        curl_easy_cleanup(curl);
        return EXIT_SUCCESS;
    }

    /*

        $headers = call_curl -Authenticate -url "$url" -otherParams
       "--output","$zipfile","--dump-header","-","--header","If-None-Match: \```"$etag\```""
        $codeheader = echo "$headers" | Select-String -CaseSensitive -Pattern "^HTTP/.*"
        if ($codeheader -match "HTTP/[^ ]* 304") {
          Write-host "Policies already up to date"
          return 0
        }
        if (-not ($codeheader -match "HTTP/[^ ]* 200")) {
          Write-host "Policy server didn't return OK code"
          return 1
        }
        $etagheader = echo $headers | Select-String -CaseSensitive -Pattern ETag
        if ($etagheader -match "ETag: `"(.*)`"") {
          $etag = $matches[1]
        }

    */

    return curl_call(curl);
}

/*
int shared_files_head(Config config, bool verbose, const char* target_id, const char* file_id,
                      const char* hash) {
    CURLcode ret = 0;
    CURL* curl = NULL;

    ret = common_options(curl, verbose, config);
    CURL_CHECK(ret);

    // Make a HEAD request
    ret = curl_easy_setopt(curl, CURLOPT_NOBODY, 1L);
    CURL_CHECK(ret);

    char url[MAX_URL_LEN];
    int length_needed =
        snprintf(url, sizeof(url),
"%s://%s:%d/rudder/relay-api/shared-files/%s/%s/%s?hash=%s",PROTOCOL,
config.server,config.https_port, target_id, config.my_id, file_id, hash);
SNPRINTF_CHECK(length_needed, sizeof(url));
    // URL to use
    ret = curl_easy_setopt(curl, CURLOPT_URL, url);
    CURL_CHECK(ret);

    return curl_call(curl);
}

int shared_files_put(const Config config, bool verbose, const char* target_id, const char* file_id,
                     char* ttl) {
    CURLcode ret = 0;
    CURL* curl = NULL;

    ret = common_options(curl, verbose, config);
    CURL_CHECK(ret);

    // Make a PUT request
    ret = curl_easy_setopt(curl, CURLOPT_UPLOAD, 1L);
    CURL_CHECK(ret);

    // --request PUT
    // --header 'Content-Type: application/octet-stream' --data-binary @-
    // echo | cat ${file_path}.sign - ${file_path}

    char url[MAX_URL_LEN];
    int length_needed =
        snprintf(url, sizeof(url), "%s://%s:%d/rudder/relay-api/shared-files/%s/%s/%s?ttl=%s",
        PROTOCOL,
                 config.server, config.https_port,target_id, config.my_id, file_id, ttl);
    SNPRINTF_CHECK(length_needed, sizeof(url));
    // URL to use
    ret = curl_easy_setopt(curl, CURLOPT_URL, url);
    CURL_CHECK(ret);

    return curl_call(curl);
}
*/
