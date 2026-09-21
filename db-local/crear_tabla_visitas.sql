-- Analítica de tráfico (visitantes, registrados vs. anónimos, vistas por producto con horario,
-- embudo de carrito). Tabla nueva y separada de eventos_usuario (que sigue igual, solo para
-- "vistos recientemente"/"categoría favorita" de un usuario YA logueado) porque eu_usr_id es
-- NOT NULL con FK dura a usuarios — no puede representar un visitante anónimo. Acá el tenant se
-- guarda directo (vis_tnd_id), a diferencia de eventos_usuario que lo resuelve indirecto vía
-- join a usuarios — porque acá el usuario casi siempre va a ser null (visitante anónimo).
CREATE TABLE visitas (
    vis_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vis_tnd_id      BIGINT NOT NULL REFERENCES tiendas(tnd_id) ON DELETE CASCADE,
    -- Identificador anónimo persistente (localStorage del navegador) — nunca un dato personal,
    -- solo sirve para contar "visitantes únicos" vs. "visitas totales" y para saber si la MISMA
    -- persona que entró de invitado luego inició sesión (se guarda igual, con vis_usr_id ya
    -- puesto, en vez de perder la conexión).
    vis_visitor_id  VARCHAR(64) NOT NULL,
    -- NULL = todavía era un invitado en el momento de este evento puntual.
    vis_usr_id      BIGINT REFERENCES usuarios(usr_id) ON DELETE SET NULL,
    vis_tipo        VARCHAR(30) NOT NULL,
    vis_entidad_tipo VARCHAR(40),
    vis_entidad_id  BIGINT,
    vis_ruta        VARCHAR(255),
    vis_dispositivo VARCHAR(20),
    vis_metadata    JSONB NOT NULL DEFAULT '{}'::jsonb,
    vis_creado_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_vis_tipo CHECK (vis_tipo IN ('pagina_vista', 'vista_producto', 'agregar_carrito')),
    CONSTRAINT chk_vis_dispositivo CHECK (vis_dispositivo IS NULL OR vis_dispositivo IN ('movil', 'escritorio'))
);

CREATE INDEX idx_vis_tnd_creado ON visitas (vis_tnd_id, vis_creado_en DESC);
CREATE INDEX idx_vis_tnd_tipo_creado ON visitas (vis_tnd_id, vis_tipo, vis_creado_en DESC);
CREATE INDEX idx_vis_tnd_producto ON visitas (vis_tnd_id, vis_entidad_id, vis_creado_en DESC) WHERE vis_tipo = 'vista_producto';
CREATE INDEX idx_vis_tnd_visitor ON visitas (vis_tnd_id, vis_visitor_id);

ALTER TABLE visitas ENABLE ROW LEVEL SECURITY;
ALTER TABLE visitas FORCE ROW LEVEL SECURITY;
CREATE POLICY pol_visitas ON visitas USING (vis_tnd_id = fn_current_tnd_id());

-- Sin esto, la tabla queda del dueño de la sesión que corrió este script (postgres si se aplica
-- como superusuario) en vez de ecommerce_owner, y la app (conectada como calzacaribe_usr) se
-- queda sin permiso para usarla — pasó de verdad al aplicar esto en local.
ALTER TABLE visitas OWNER TO ecommerce_owner;

-- El dueño de la tabla NO le da automáticamente acceso a otros roles — hace falta el GRANT
-- explícito, igual que ya tiene el resto de tablas (ver "\dp productos" para comparar).
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER ON visitas TO calzacaribe_usr;
GRANT SELECT ON visitas TO ecommerce_readonly;
GRANT USAGE, SELECT ON SEQUENCE visitas_vis_id_seq TO calzacaribe_usr;
