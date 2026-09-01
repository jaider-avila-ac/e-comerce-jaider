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

-- ============================================================================
-- 2026-08-30 — Fase 2 (§5): tabla de dominios por tienda, para resolver el tenant de una
-- solicitud pública por Host real en vez de depender solo de X-Tenant-Id (ver
-- TenantDomainResolver + TenantInterceptor). Sin RLS a propósito — igual que `tiendas`, tiene
-- que poder leerse ANTES de que exista contexto de tenant (es la tabla que lo resuelve).
--
-- Dominio real del storefront confirmado por el usuario (2026-08-30): tienda.calzacaribe.com
-- (la primera inferencia, calzacaribe.com/www.calzacaribe.com a partir de tnd_dominio_staff,
-- era incorrecta — corregido).
--
-- También hace falta que el proxy de la tienda (tienda/nginx.conf, location = /sitemap.xml)
-- mande X-Forwarded-Host con el dominio real del storefront — hoy solo reescribe Host al
-- dominio del backend para el ruteo TLS, y TenantInterceptor no tiene otra forma de saber cuál
-- era el Host original del navegador en ese proxy_pass específico.
-- ============================================================================
CREATE TABLE tienda_dominios (
  tdo_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tdo_tnd_id BIGINT NOT NULL REFERENCES tiendas(tnd_id) ON DELETE CASCADE,
  tdo_dominio VARCHAR(255) NOT NULL,
  tdo_principal BOOLEAN NOT NULL DEFAULT false,
  tdo_activo BOOLEAN NOT NULL DEFAULT true,
  tdo_verificado_en TIMESTAMPTZ,
  tdo_creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_tienda_dominios_dominio UNIQUE (tdo_dominio)
);
CREATE INDEX idx_tienda_dominios_tnd_id ON tienda_dominios(tdo_tnd_id);
GRANT SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER ON TABLE tienda_dominios TO calzacaribe_usr;
GRANT SELECT,USAGE ON SEQUENCE tienda_dominios_tdo_id_seq TO calzacaribe_usr;

INSERT INTO tienda_dominios (tdo_tnd_id, tdo_dominio, tdo_principal, tdo_activo) VALUES
  (1, 'tienda.calzacaribe.com', true, true);

-- ============================================================================
-- 2026-08-30 — Fase 2 (§4.1/§4.2/§8.3): campos de identidad de marca para el
-- TenantBrandingContext (correos ya no dicen "Calzacaribe" a fuego, usan estos campos).
-- Quedan NULL para Calzado Caribe a propósito — el usuario los completa desde el panel
-- (TiendaConfigService ya expone razonSocial/nit/emailContacto/colorPrincipal editables), no
-- hay datos reales fiables para inventarlos acá.
-- ============================================================================
ALTER TABLE tiendas ADD COLUMN tnd_razon_social VARCHAR(200);
ALTER TABLE tiendas ADD COLUMN tnd_nit VARCHAR(30);
ALTER TABLE tiendas ADD COLUMN tnd_email_contacto VARCHAR(255);
ALTER TABLE tiendas ADD COLUMN tnd_color_principal VARCHAR(7);

-- ============================================================================
-- 2026-08-30 — Fase 2 (§4.2): generalizar contactos por sucursal en vez de columnas fijas
-- en `tiendas` tipo tnd_whatsapp_la_paz — exactamente el anti-patrón que el plan nombra por
-- nombre ("No deben existir campos específicos como whatsappLaPaz"). Verificado que
-- tnd_whatsapp_la_paz no lo leía ni el backend ni ningún frontend (admin/tienda/sitio-web) y
-- que su valor real en el VPS está vacío — no hay nada que migrar, solo eliminar la columna.
-- ============================================================================
ALTER TABLE sucursales ADD COLUMN suc_whatsapp VARCHAR(20);
ALTER TABLE tiendas DROP COLUMN tnd_whatsapp_la_paz;

-- ============================================================================
-- 2026-08-30 — Corrección de diseño del superadmin (§11.2), pedida por el usuario DESPUÉS
-- del cierre de Fase 3: el superadmin NO debe poder elegir una tienda y operar como su admin
-- (eso se había implementado así en un primer intento y era exactamente lo que el usuario NO
-- quería — acceso sin consentimiento del dueño de la tienda). Corregido: el superadmin solo
-- puede ver TOTALES agregados de toda la plataforma (nunca datos operativos ni por tienda), vía
-- una función SQL SECURITY DEFINER — el único punto autorizado a cruzar el RLS de todas las
-- tiendas, y solo para devolver conteos, nunca filas individuales.
--
-- IMPORTANTE: debe quedar dueña de `postgres` (superusuario) para que SECURITY DEFINER
-- bypasee RLS de verdad — si se crea como cualquier otro rol (incluido ecommerce_owner, que NO
-- tiene BYPASSRLS) la función seguiría bloqueada por el FORCE ROW LEVEL SECURITY de las tablas.
-- ============================================================================
CREATE FUNCTION fn_superadmin_resumen()
RETURNS TABLE(tiendas_activas bigint, total_clientes bigint)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT
    (SELECT count(*) FROM tiendas WHERE tnd_activo = true),
    (SELECT count(*) FROM usuarios);
$$;
GRANT EXECUTE ON FUNCTION fn_superadmin_resumen() TO calzacaribe_usr;

-- ============================================================================
-- 2026-08-31 — Credenciales de integración (Wompi/Resend/Cloudinary) por tienda, cifradas en
-- BD — decisión explícita del usuario, reemplaza la dependencia exclusiva de variables de
-- entorno para tiendas NUEVAS creadas desde el panel de superadmin (§6 del plan). Las tiendas
-- que ya usan variables de entorno (Calzacaribe) siguen funcionando igual — TenantIntegration-
-- Resolver ahora consulta esta tabla PRIMERO y solo cae a la variable de entorno si no hay fila.
--
-- Sin RLS a propósito, mismo criterio que `tiendas`: la toca (a) el superadmin, que administra
-- TODAS las tiendas, no una sola, y (b) TenantIntegrationResolver, que siempre recibe el tndId
-- de un parámetro Java ya resuelto de forma confiable (JWT/contexto), nunca de un valor de
-- cliente sin validar.
--
-- IMPORTANTE: además de este DDL, el VPS necesita su PROPIA variable SECRETS_ENCRYPTION_KEY
-- (256 bits, base64) — generada aparte para el VPS, NUNCA la misma que la de este entorno
-- local. Sin ella, guardar o leer cualquier credencial desde esta tabla falla con un error
-- claro (nunca se inventa ni se reusa una llave por defecto).
-- ============================================================================
CREATE TABLE tienda_secretos (
    tse_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tse_tnd_id BIGINT NOT NULL REFERENCES tiendas(tnd_id) ON DELETE CASCADE,
    tse_proveedor VARCHAR(20) NOT NULL,
    tse_campo VARCHAR(30) NOT NULL,
    tse_valor_cifrado TEXT NOT NULL,
    tse_actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    tse_actualizado_por BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    UNIQUE(tse_tnd_id, tse_proveedor, tse_campo)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON tienda_secretos TO calzacaribe_usr;
GRANT USAGE, SELECT ON SEQUENCE tienda_secretos_tse_id_seq TO calzacaribe_usr;
ALTER TABLE tienda_secretos OWNER TO ecommerce_owner;

-- ============================================================================
-- 2026-08-31 — PLAN_INTEGRACION_ENVIA.md, Fase 0: agrega 'envia' como tercer modo de envío
-- (junto a 'contra_entrega' y 'fijo', que ya existían) — OPCIONAL por tienda, Calzacaribe se
-- queda en su modo actual sin cambio. tnd_envia_ambiente distingue sandbox/producción, solo
-- relevante si tnd_envio_modo='envia'.
-- ============================================================================
ALTER TABLE tiendas DROP CONSTRAINT tiendas_tnd_envio_modo_check;
ALTER TABLE tiendas ADD CONSTRAINT tiendas_tnd_envio_modo_check
    CHECK (tnd_envio_modo::text = ANY (ARRAY['contra_entrega','fijo','envia']::text[]));
ALTER TABLE tiendas ADD COLUMN tnd_envia_ambiente VARCHAR(20) NOT NULL DEFAULT 'sandbox'
    CHECK (tnd_envia_ambiente IN ('sandbox','produccion'));

-- ============================================================================
-- 2026-08-31 — PLAN_INTEGRACION_ENVIA.md, Fase 1: catálogo de empaques por tienda (RLS
-- forzado, igual que el resto de tablas de negocio — a diferencia de `tiendas`/
-- `tienda_secretos`, que son cross-tenant a propósito). Diseño final tras 2 correcciones del
-- usuario en la misma sesión (se documentan las 2 intermedias por trazabilidad, pero este
-- bloque ya refleja el resultado, no las versiones descartadas):
--   1ra corrección: no repetir peso/dimensiones en cada fila de `productos` — sacarlo a una
--     tabla reutilizable aparte (normalización).
--   2da corrección: "las cajas son las que tienen que tener las medidas... no se mide al
--     zapato, se mide la caja" + "el peso no va en el producto, va también en la caja" — un
--     producto no tiene una especificación propia en absoluto, se asigna DIRECTO a un empaque
--     (peso + dimensiones juntos). Si dos productos comparten caja pero pesan distinto, el
--     admin crea dos filas de empaque con las mismas medidas y distinto peso.
--
-- Consecuencia en el cálculo (ver PaqueteCalculoService, Fase 1): la API real de Envia.com
-- (docs.envia.com/docs/shipping-multiple-packages) acepta un arreglo `packages[]` — un renglón
-- por cada empaque distinto usado en el carrito, con su propio peso/dimensiones — y ENVIA
-- MISMO suma todo para cotizar. Por eso no hace falta un rango de "cantidad de artículos que
-- cubre esta caja" (ya no existe `tep_cantidad_min/max`): cada línea del carrito aporta su
-- propio renglón al arreglo, agrupado por empaque.
--
-- chk_tep_medida_maxima: 50cm por lado — límite real publicado por Coordinadora
-- (coordinadora.com/envios/cotizar-un-envio), para que una tienda no pueda crear un empaque
-- que ninguna transportadora acepte.
-- ============================================================================
CREATE TABLE tienda_empaques (
    tep_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tep_tnd_id BIGINT NOT NULL REFERENCES tiendas(tnd_id) ON DELETE CASCADE,
    tep_nombre VARCHAR(60) NOT NULL,
    tep_largo_cm SMALLINT NOT NULL CHECK (tep_largo_cm > 0),
    tep_ancho_cm SMALLINT NOT NULL CHECK (tep_ancho_cm > 0),
    tep_alto_cm SMALLINT NOT NULL CHECK (tep_alto_cm > 0),
    tep_peso_gramos INTEGER NOT NULL CHECK (tep_peso_gramos >= 0),
    tep_orden SMALLINT NOT NULL DEFAULT 0,
    tep_activo BOOLEAN NOT NULL DEFAULT true,
    tep_creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tep_tnd_id, tep_nombre),
    CONSTRAINT chk_tep_medida_maxima CHECK (tep_largo_cm <= 50 AND tep_ancho_cm <= 50 AND tep_alto_cm <= 50)
);
CREATE INDEX idx_tep_tnd_id ON tienda_empaques(tep_tnd_id);

ALTER TABLE tienda_empaques ENABLE ROW LEVEL SECURITY;
ALTER TABLE tienda_empaques FORCE ROW LEVEL SECURITY;
CREATE POLICY pol_tienda_empaques ON tienda_empaques
    USING (tep_tnd_id = fn_current_tnd_id())
    WITH CHECK (tep_tnd_id = fn_current_tnd_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON tienda_empaques TO calzacaribe_usr;
GRANT USAGE, SELECT ON SEQUENCE tienda_empaques_tep_id_seq TO calzacaribe_usr;
ALTER TABLE tienda_empaques OWNER TO ecommerce_owner;

-- ON DELETE SET NULL (no CASCADE): borrar un empaque no debe borrar los productos que lo usan,
-- solo dejarlos sin empaque asignado (vuelven a quedar "sin poder calcular envío" hasta que el
-- admin les asigne otro).
ALTER TABLE productos ADD COLUMN prd_empaque_id BIGINT
    REFERENCES tienda_empaques(tep_id) ON DELETE SET NULL;
CREATE INDEX idx_prd_empaque_id ON productos(prd_empaque_id);

-- ============================================================================
-- 2026-08-31 — PLAN_INTEGRACION_ENVIA.md, Fase 3: código postal del cliente (Envia lo exige
-- sí o sí para cotizar en Colombia, confirmado con una llamada real) + dirección de ORIGEN
-- (desde dónde recoge la transportadora) — opcional, solo se exige si la tienda activa el
-- modo 'envia'.
--
-- CORRECCIÓN (pedida por el usuario en la misma sesión): la dirección de origen NO va en
-- `tiendas` — una tienda puede tener varias sucursales físicas (ver [[sucursales_tiendas_
-- fisicas]]), y `sucursales` ya es la entidad correcta para "ubicación física". Cada sucursal
-- puede tener su propia dirección de recogida, aunque hoy (Fase 3) todavía no se elige cuál
-- usar — se deja disponible para cuando haga falta, en vez de duplicar el concepto en tiendas.
-- ============================================================================
ALTER TABLE clientes_direcciones ADD COLUMN cd_codigo_postal VARCHAR(10);

ALTER TABLE sucursales ADD COLUMN suc_envio_origen_nombre VARCHAR(150);
ALTER TABLE sucursales ADD COLUMN suc_envio_origen_telefono VARCHAR(40);
ALTER TABLE sucursales ADD COLUMN suc_envio_origen_direccion VARCHAR(255);
ALTER TABLE sucursales ADD COLUMN suc_envio_origen_complemento VARCHAR(255);
ALTER TABLE sucursales ADD COLUMN suc_envio_origen_departamento VARCHAR(100);
ALTER TABLE sucursales ADD COLUMN suc_envio_origen_municipio VARCHAR(100);
ALTER TABLE sucursales ADD COLUMN suc_envio_origen_codigo_postal VARCHAR(10);

-- ============================================================================
-- 2026-08-31 — PLAN_INTEGRACION_ENVIA.md, Fase 3: cotización real en checkout — orden de
-- transportadoras configurable POR TIENDA (pedido explícito del usuario: "ese orden lo pueda
-- decidir el administrador"). Si una tienda no configura nada, EnvioCotizacionService usa un
-- orden por defecto (Servientrega, Coordinadora, InterRapidísimo — los 3 confirmados cotizando
-- en vivo; "envia"/Envía Colombia queda fuera del default hasta confirmar que de verdad presta
-- servicio, ver memoria de la sesión) — la tabla existe para que el admin lo cambie, no para
-- que sea obligatoria.
-- ============================================================================
CREATE TABLE tienda_transportadoras (
    ttr_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ttr_tnd_id BIGINT NOT NULL REFERENCES tiendas(tnd_id) ON DELETE CASCADE,
    ttr_carrier VARCHAR(40) NOT NULL,
    ttr_orden SMALLINT NOT NULL DEFAULT 0,
    ttr_activo BOOLEAN NOT NULL DEFAULT true,
    ttr_creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(ttr_tnd_id, ttr_carrier)
);
CREATE INDEX idx_ttr_tnd_id ON tienda_transportadoras(ttr_tnd_id);

ALTER TABLE tienda_transportadoras ENABLE ROW LEVEL SECURITY;
ALTER TABLE tienda_transportadoras FORCE ROW LEVEL SECURITY;
CREATE POLICY pol_tienda_transportadoras ON tienda_transportadoras
    USING (ttr_tnd_id = fn_current_tnd_id())
    WITH CHECK (ttr_tnd_id = fn_current_tnd_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON tienda_transportadoras TO calzacaribe_usr;
GRANT USAGE, SELECT ON SEQUENCE tienda_transportadoras_ttr_id_seq TO calzacaribe_usr;
ALTER TABLE tienda_transportadoras OWNER TO ecommerce_owner;

-- ============================================================================
-- 2026-08-31 — PLAN_INTEGRACION_ENVIA.md, Fase 4: generación de guía REAL desde el panel del
-- admin (POST /ship/generate/ de Envia — a diferencia de /ship/rate/, esto SÍ cobra de la
-- cuenta de la tienda en Envia). Se reutilizan las columnas de seguimiento que YA existían
-- (ped_transportadora/ped_codigo_rastreo/ped_link_seguimiento, hasta ahora llenadas a mano por
-- el admin) — se llenan solas cuando se genera la guía real. Estas 3 columnas nuevas son lo que
-- NO existía: el id interno de Envia (para poder cancelar más adelante), la URL del PDF
-- imprimible, y el costo real cobrado (puede diferir un poco de la cotización de la Fase 3).
-- ============================================================================
ALTER TABLE pedidos ADD COLUMN ped_envia_shipment_id VARCHAR(50);
ALTER TABLE pedidos ADD COLUMN ped_envia_guia_url VARCHAR(500);
ALTER TABLE pedidos ADD COLUMN ped_envia_costo_real_centavos BIGINT;

-- ============================================================================
-- 2026-09-01 — PLAN_INTEGRACION_ENVIA.md, correcciones de una auditoría estática real (3
-- críticos + varios altos, ninguno hipotético — se verificó cada uno contra el código real antes
-- de corregir). Este campo resuelve DOS hallazgos a la vez:
--   - "Las dimensiones del envío no quedan congeladas en el pedido": generar la guía volvía a
--     calcular el paquete desde el producto/empaque ACTUALES — si el admin cambia o borra el
--     empaque después de la compra, la guía real terminaría con medidas distintas a las que se
--     le cotizaron al cliente (o fallaría). Ahora se congela en el checkout.
--   - "La cotización cobrada no está vinculada a la guía": no había ningún rastro de qué
--     transportadora/servicio/precio se cotizó al cliente — el admin podía generar la guía con
--     cualquier transportadora, incluso una bastante más cara, sin ninguna referencia.
-- ============================================================================
ALTER TABLE pedidos ADD COLUMN ped_envio_cotizacion_snapshot JSONB;
