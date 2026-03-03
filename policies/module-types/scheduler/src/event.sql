create table if not exists schedule_events (
    id integer primary key,

    -- IDs
    -- use nocase to allow using index for "like" queries
    event_id text    not null collate nocase unique,
    schedule_id text not null collate nocase,

    -- once, always, never
    schedule not null,

    -- benchmark, system-update, etc.
    schedule_type text not null,
    -- event name
    event_name text not null,
    -- insertion datetime
    created text not null,

    -- timestamps
    -- stored as rfc3339 string (https://rust10x.com/post/sqlite-time-text-vs-integer)
    run_time text,
    not_before text,
    not_after text
);

create index if not exists idx_event_id    on schedule_events (event_id);
create index if not exists idx_schedule_id on schedule_events (schedule_id);
