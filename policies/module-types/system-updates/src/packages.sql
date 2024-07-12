-- Database for package management

-- For JSON data, we store the text as blob, as we don't do any query inside the JSON.
-- The data validity is ensured by the application.

-- Update events
CREATE TABLE IF NOT EXISTS update_event (
    id INTEGER PRIMARY KEY,
    -- event_id
    event_id TEXT NOT NULL,
    -- started, pending-report, completed
    status TEXT NOT NULL,
    -- timestamps
    run_datetime TEXT NOT NULL,
    report_datetime TEXT,
    -- report sent to the server
    report TEXT -- json string
);
