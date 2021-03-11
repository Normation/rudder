/**
 * Copyright (c) 2020 rxi
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the MIT license. See `log.c` for details.
 */

#ifndef LOG_H
#define LOG_H

#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <time.h>

#define LOG_VERSION "0.1.0"

typedef struct {
    va_list ap;
    const char* fmt;
    const char* file;
    struct tm* time;
    void* udata;
    int line;
    int level;
} log_Event;

typedef void (*log_LogFn)(log_Event* ev);
typedef void (*log_LockFn)(bool lock, void* udata);

enum { LOG_TRACE, LOG_DEBUG, LOG_INFO, LOG_WARN, LOG_ERROR, LOG_NONE };

#define trace(...) log_log(LOG_TRACE, __FILE__, __LINE__, __VA_ARGS__)
#define debug(...) log_log(LOG_DEBUG, __FILE__, __LINE__, __VA_ARGS__)
#define info(...) log_log(LOG_INFO, __FILE__, __LINE__, __VA_ARGS__)
#define warn(...) log_log(LOG_WARN, __FILE__, __LINE__, __VA_ARGS__)
#define error(...) log_log(LOG_ERROR, __FILE__, __LINE__, __VA_ARGS__)

void log_set_level(int level);
void log_set_color(bool enable);

void log_log(int level, const char* file, int line, const char* fmt, ...);

void output(const char* text);
char* output_get(void);
void output_set_enabled(bool enabled);
void output_free(void);

#endif
