-- Cambios de esquema hechos en la BD LOCAL (rama feature/multitenant-plan) que hay que
-- replicar a mano en el VPS (consola, como superusuario `postgres`) cuando el usuario
-- confirme que se despliega esta rama. No usar Flyway (decisión ya tomada antes, ver
-- memoria del proyecto) — este archivo es solo un registro para copiar/pegar por consola,
-- igual que se ha hecho con cada cambio de esquema anterior en este proyecto.
--
-- Cada bloque lleva fecha y referencia a la sección del plan que lo motiva.

-- ============================================================================
-- 2026-08-30 — Fase 0 (PLAN_MEJORAS_API_ECOMMERCE_MULTITENANT.md §10.2/§17):
-- 3 tablas tenían RLS habilitado pero SIN "FORCE", y como su dueño es
-- calzacaribe_usr (el mismo rol que usa la API), Postgres deja que el DUEÑO
-- se salte las políticas por default salvo que se fuerce explícitamente.
-- Resultado real en producción ahora mismo: cualquier query de la API contra
-- colecciones/coleccion_productos/idempotencia_operaciones devuelve filas de
-- TODAS las tiendas, no solo la del tenant actual. (tiendas se deja sin FORCE
-- a propósito — es la tabla raíz de config pública, no una excepción por descuido.)
-- ============================================================================
ALTER TABLE colecciones FORCE ROW LEVEL SECURITY;
ALTER TABLE coleccion_productos FORCE ROW LEVEL SECURITY;
ALTER TABLE idempotencia_operaciones FORCE ROW LEVEL SECURITY;

-- intentos_login no tenía RLS en absoluto (ninguna política, rowsecurity=false). No la usa
-- ningún código Java todavía (tabla sin wire-up, probablemente reservada para un rate-limit
-- de login que nunca se terminó de implementar) pero ya tiene columna il_tnd_id — se le da
-- el mismo patrón de RLS que al resto por si se activa más adelante, sin esperar a que
-- alguien la use primero para darse cuenta de que no estaba protegida.
ALTER TABLE intentos_login ENABLE ROW LEVEL SECURITY;
ALTER TABLE intentos_login FORCE ROW LEVEL SECURITY;
CREATE POLICY pol_intentos_login ON intentos_login
  USING (il_tnd_id = fn_current_tnd_id())
  WITH CHECK (il_tnd_id = fn_current_tnd_id());
GRANT SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER ON TABLE intentos_login TO calzacaribe_usr;
GRANT SELECT,USAGE ON SEQUENCE intentos_login_il_id_seq TO calzacaribe_usr;
