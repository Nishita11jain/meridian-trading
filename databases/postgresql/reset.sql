-- Drops everything this project creates so schema.sql / migrations and seed-data.sql can be re-run
-- from scratch. DESTROYS ALL DATA. Usage: psql -d <db> -f reset.sql
DROP SCHEMA IF EXISTS auth CASCADE;
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
