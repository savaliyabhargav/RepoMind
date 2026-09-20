-- Each microservice owns its own database on this shared Postgres instance.
-- Runs once, only when the postgres_data volume is first initialized.
CREATE DATABASE auth_db;
CREATE DATABASE repo_db;
CREATE DATABASE explain_db;
CREATE DATABASE analysis_db;
