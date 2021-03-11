/*
 * Copyright (c) 2020 rxi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

#pragma GCC diagnostic ignored "-Wformat-nonliteral"

#include "log.h"
#include <stdlib.h>
#include "utils.h"

static struct {
    void* udata;
    // keep locking feature in case we add multithreading
    log_LockFn lock;
    int level;
    bool color;
} L;

// Program output
// Used for testing
static struct {
    char* last;
    bool enabled;
} O;

void output(const char* text) {
    O.last = strdup_compat(text);
    if (O.enabled) {
        printf("%s\n", text);
    }
}

char* output_get(void) {
    return O.last;
}

void output_set_enabled(bool enabled) {
    O.enabled = enabled;
}

void output_free(void) {
    if (O.last != NULL) {
        free(O.last);
    }
}

static const char* const level_strings[] = { "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "NONE" };
static const char* const level_colors[] = { "\x1b[94m", "\x1b[36m", "\x1b[32m",
                                            "\x1b[33m", "\x1b[31m", "" };

static void stdout_callback(log_Event* ev) {
    if (L.color) {
        fprintf(ev->udata, "%s%-5s\x1b[0m ", level_colors[ev->level], level_strings[ev->level]);
    } else {
        fprintf(ev->udata, "%-5s ", level_strings[ev->level]);
    }
    vfprintf(ev->udata, ev->fmt, ev->ap);
    fprintf(ev->udata, "\n");
    fflush(ev->udata);
}

static void lock(void) {
    if (L.lock) {
        L.lock(true, L.udata);
    }
}

static void unlock(void) {
    if (L.lock) {
        L.lock(false, L.udata);
    }
}

void log_set_level(int level) {
    L.level = level;
}

void log_set_color(bool enable) {
    L.color = enable;
}

static void init_event(log_Event* ev, void* udata) {
    if (!ev->time) {
        time_t t = time(NULL);
        ev->time = localtime(&t);
    }
    ev->udata = udata;
}

void log_log(int level, const char* file, int line, const char* fmt, ...) {
    log_Event ev = {
        .fmt = fmt,
        .file = file,
        .line = line,
        .level = level,
    };

    lock();

    if (level >= L.level) {
        init_event(&ev, stderr);
        va_start(ev.ap, fmt);
        stdout_callback(&ev);
        va_end(ev.ap);
    }

    unlock();
}
