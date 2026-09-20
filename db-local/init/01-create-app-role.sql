-- Recrea el rol de aplicacion (no-superusuario) tal como existe en el VPS,
-- para que el restore del dump (hecho como 'postgres') deje las tablas con el
-- mismo dueño/RLS que en produccion. La password real se setea despues del
-- restore con ALTER ROLE (ver setup-local-db.sh), este archivo solo crea el rol.
CREATE ROLE calzacaribe_usr LOGIN PASSWORD 'changeme';
