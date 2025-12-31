-- Update events
create table if not exists schedule_events (
    id integer primary key,

    -- benchmark, system-update, etc.
    event_type text not null,
    -- event_id
    -- use nocase to allow using index for "like" queries
    event_id text not null collate nocase unique,
    -- event name
    event_name text not null,
    -- insertion datetime
    created text not null,

    -- timestamps
    -- stored as rfc3339 string (https://rust10x.com/post/sqlite-time-text-vs-integer)
    datetime text,
    not_before text,
    not_after text,
);

create index if not exists idx_event_id on schedule_events (event_id);
