-- Database for package management

-- For JSON data, we store the text as blob, as we don't do any query inside the JSON.
-- The data validity is ensured by the application.

-- Update events
create table if not exists update_events (
    id integer primary key,
    -- event_id
    -- use nocase to allow using index for like queries
    event_id text not null collate nocase unique,
    -- campaign name
    campaign_name text not null,
    -- scheduled, running, pending-post-action, running-post-action, completed
    status text not null,

    -- timestamps
    -- stored as rfc3339 string (https://rust10x.com/post/sqlite-time-text-vs-integer)
    schedule_datetime text not null,
    -- will be slightly after schedule_timestamp
    run_datetime text,
    report_datetime text,

    -- report sent to the server
    report text, -- json string

    -- process id of the current process handling the upgrade
    -- when not null, acts as a lock
    pid integer
);

create index if not exists idx_event_id on update_events (event_id);
