-- installed packages
CREATE TABLE IF NOT EXISTS installed (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    architecture TEXT NOT NULL
);

-- available updates
CREATE TABLE IF NOT EXISTS updates (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    architecture TEXT NOT NULL
);

-- key-values
CREATE TABLE IF NOT EXISTS kv (
    id INTEGER PRIMARY KEY,
    key_ TEXT NOT NULL,
    value TEXT NOT NULL
);
