-- Remove parking ownership (product: no 产权/非产权). Safe to re-run if column already gone.
-- MySQL 8.0.29+ supports IF EXISTS; older versions run once manually.

ALTER TABLE parking_space DROP COLUMN IF EXISTS ownership;
