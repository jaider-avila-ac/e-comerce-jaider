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

-- ============================================================================
-- 2026-08-30 — Fase 0 (§10.6/§11.1/§17): unique constraints que debían ser por
-- tenant y eran globales.
--
-- usuarios_usr_google_id_key (UNIQUE global) bloqueaba justo el caso que el plan
-- exige permitir: la misma cuenta Google registrándose en dos tiendas distintas
-- (UsuarioAuthService.loginConGoogle intenta el INSERT y el segundo tenant
-- chocaba con un 409 confuso — "registro relacionado con otros datos", porque
-- ApiExceptionHandler no tiene un mensaje específico para ese constraint).
-- Verificado: mismo email+google_id insertado en tenant 1 y tenant 2 -> ya no falla.
--
-- pedidos_ped_numero_key (UNIQUE global) no es un hueco de seguridad (RLS ya
-- limita lo que ve el chequeo de colisión de generarNumeroUnico() al tenant
-- actual) pero sí un riesgo real de conflicto: dos tiendas distintas podían
-- generar el mismo PED{fecha}-{random} el mismo día y el INSERT fallaría con
-- un 409 aunque para cada tienda, vista con RLS, el número "no estaba tomado".
-- ============================================================================
ALTER TABLE usuarios DROP CONSTRAINT usuarios_usr_google_id_key;
ALTER TABLE usuarios ADD CONSTRAINT uq_usuarios_google_id_tnd UNIQUE (usr_tnd_id, usr_google_id);

ALTER TABLE pedidos DROP CONSTRAINT pedidos_ped_numero_key;
ALTER TABLE pedidos ADD CONSTRAINT uq_pedidos_numero_tnd UNIQUE (ped_tnd_id, ped_numero);

-- ============================================================================
-- 2026-08-30 — Fase 0 (§10.1): separación de roles Postgres.
--
-- Antes: calzacaribe_usr (el rol que usa la API) era DUEÑO de todas las tablas,
-- justo lo que el plan pide evitar ("ecommerce_app... No debe ser propietario
-- de las tablas"). El riesgo real de esto (que el dueño se salte RLS) ya se
-- había cerrado forzando RLS en todas las tablas, pero seguía sin cumplir la
-- separación de roles que pide la sección 10.1.
--
-- IMPORTANTE antes de correr esto en el VPS:
--   1. Cambiar las dos contraseñas de más abajo (son placeholders locales).
--   2. Correrlo completo en una sola sesión/transacción — REASSIGN OWNED
--      mueve TODO lo que sea dueño calzacaribe_usr (tablas, vistas,
--      funciones, secuencias) a ecommerce_owner de un solo golpe; si algo
--      queda a medias, el segundo bloque (los GRANT explícitos) es el que
--      le devuelve a calzacaribe_usr lo que necesita para seguir operando.
--   3. Reiniciar el backend después (pool de conexiones/planes cacheados).
--   4. Verificar con un smoke test amplio (login, listar pedidos/productos/
--      categorías/colecciones, crear un producto, catálogo público, sitemap)
--      antes de dar por buena la migración — se probó así en local y todo
--      siguió funcionando igual, pero el VPS es la base real.
--
-- No se le da BYPASSRLS a ecommerce_owner — las migraciones que necesiten ver
-- todos los tenants a la vez se siguen haciendo con el superusuario `postgres`
-- (igual que ya se hacía antes, ver memoria del proyecto), no con este rol.
-- ============================================================================
CREATE ROLE ecommerce_owner LOGIN PASSWORD 'CAMBIAR_ANTES_DE_USAR_EN_VPS';
CREATE ROLE ecommerce_readonly LOGIN PASSWORD 'CAMBIAR_ANTES_DE_USAR_EN_VPS';
GRANT USAGE ON SCHEMA public TO calzacaribe_usr, ecommerce_readonly;

REASSIGN OWNED BY calzacaribe_usr TO ecommerce_owner;

DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='public' LOOP
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE public.%I TO calzacaribe_usr', r.tablename);
    EXECUTE format('GRANT SELECT ON TABLE public.%I TO ecommerce_readonly', r.tablename);
  END LOOP;
  FOR r IN SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema='public' LOOP
    EXECUTE format('GRANT SELECT, USAGE ON SEQUENCE public.%I TO calzacaribe_usr', r.sequence_name);
    EXECUTE format('GRANT SELECT ON SEQUENCE public.%I TO ecommerce_readonly', r.sequence_name);
  END LOOP;
  -- Las vistas (v_categorias_activas, v_inventario_simple, v_subcategorias_activas,
  -- v_variantes_con_stock) NO están en pg_tables — hay que concederlas aparte.
  FOR r IN SELECT viewname FROM pg_views WHERE schemaname='public' LOOP
    EXECUTE format('GRANT SELECT ON TABLE public.%I TO calzacaribe_usr', r.viewname);
    EXECUTE format('GRANT SELECT ON TABLE public.%I TO ecommerce_readonly', r.viewname);
  END LOOP;
END $$;

-- Para que las próximas tablas/vistas que cree ecommerce_owner (vía migraciones futuras)
-- ya nazcan con estos permisos, sin tener que acordarse del GRANT manual cada vez.
ALTER DEFAULT PRIVILEGES FOR ROLE ecommerce_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES TO calzacaribe_usr;
ALTER DEFAULT PRIVILEGES FOR ROLE ecommerce_owner IN SCHEMA public
  GRANT SELECT, USAGE ON SEQUENCES TO calzacaribe_usr;
ALTER DEFAULT PRIVILEGES FOR ROLE ecommerce_owner IN SCHEMA public
  GRANT SELECT ON TABLES TO ecommerce_readonly;

-- ============================================================================
-- 2026-08-30 — Regresión encontrada DESPUÉS de aplicar lo de arriba (§10.1):
-- `tiendas` tenía RLS `ENABLE` pero CERO políticas (`pg_policies` vacío para
-- esa tabla) — "funcionaba" solo porque calzacaribe_usr era el dueño y sin
-- FORCE el dueño se salta RLS igual. Al reasignar el ownership a
-- ecommerce_owner, calzacaribe_usr dejó de poder leer `tiendas` en absoluto
-- (RLS enabled + 0 políticas = deniega todo para quien no es dueño) — daba
-- 404 "Tienda no encontrada" en /api/v1/public/tienda/config para CUALQUIER
-- tenant. `tiendas` es la tabla raíz para resolver qué tienda es cada
-- solicitud (necesita poder leerse sin tener aún un tenant resuelto — ver
-- TiendaConfigService.currentTienda(), que siempre deriva el id del contexto
-- ya autenticado, nunca de un parámetro del cliente, así que la protección de
-- escritura no depende de RLS acá). La solución correcta es deshabilitar RLS
-- en esta tabla explícitamente (no dejarla en un estado a medias que solo
-- "funcionaba" por accidente de ownership).
-- Se auditaron TODAS las demás tablas por este mismo patrón (RLS enabled +
-- cero políticas) y `tiendas` fue la única.
-- ============================================================================
ALTER TABLE tiendas DISABLE ROW LEVEL SECURITY;

-- ============================================================================
-- 2026-08-30 — Fase 1 (§4.4/§6): alias de secretos por tienda.
--
-- Requisito para TenantIntegrationResolver: cada tienda necesita un alias
-- neutral e inmutable para armar el nombre de sus variables de entorno
-- (CLOUDINARY_<alias>_*, y más adelante RESEND_<alias>_*/WOMPI_<alias>_*).
-- El alias de Calzado Caribe en el VPS debe quedar CALZADO_CARIBE para que
-- coincida con las variables CLOUDINARY_CALZADO_CARIBE_* que hay que crear
-- en el entorno del VPS (renombrando las actuales CLOUDINARY_CLOUD_NAME/
-- API_KEY/API_SECRET, que dejan de leerse una vez desplegada esta rama).
-- ============================================================================
ALTER TABLE tiendas ADD COLUMN tnd_secret_alias varchar(60);
UPDATE tiendas SET tnd_secret_alias = 'CALZADO_CARIBE' WHERE tnd_id = 1;
-- Si en el VPS ya existiera más de una tienda real para cuando se aplique esto,
-- hay que darle un alias único a cada una ANTES del siguiente ALTER (que exige
-- NOT NULL para todas las filas).
ALTER TABLE tiendas ALTER COLUMN tnd_secret_alias SET NOT NULL;
ALTER TABLE tiendas ADD CONSTRAINT uq_tiendas_secret_alias UNIQUE (tnd_secret_alias);
ALTER TABLE tiendas ADD CONSTRAINT chk_tiendas_secret_alias_formato CHECK (tnd_secret_alias ~ '^[A-Z0-9_]+$');
