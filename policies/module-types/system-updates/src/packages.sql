
-- liste des packages installés:
--
-- plusieurs types d'accès :
-- * global pour diff
-- * accès random pour vérifier présence/version





-- installed packages
CREATE TABLE IF NOT EXISTS installed (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    architecture TEXT NOT NULL
);

-- update campaigns
CREATE TABLE IF NOT EXISTS campaigns (
    id INTEGER PRIMARY KEY,
    -- timestamps
    creation_date TEXT NOT NULL,
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL,
    report_date TEXT NOT NULL,
    -- JSON
    report BLOB,
) STRICT;
