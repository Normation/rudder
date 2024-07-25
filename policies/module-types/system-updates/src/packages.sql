-- Database for package management

-- For JSON data, we store the text as blob, as we don't do any query inside the JSON.
-- The data validity is ensured by the application.

-- Update events
create table if not exists update_events (
    id integer primary key,
    -- event_id
    event_id text not null,
    -- campaign name
    campaign_name text not null,
    -- scheduled, running, pending-post-action, completed
    status text not null,
    -- timestamps
    -- stored as rfc3339 string (https://rust10x.com/post/sqlite-time-text-vs-integer)
    schedule_datetime text not null,
    -- will be slightly after schedule_timestamp
    run_datetime text,
    -- FIXME end_datetime?
    report_datetime text,
    -- report sent to the server
    report text -- json string
);
