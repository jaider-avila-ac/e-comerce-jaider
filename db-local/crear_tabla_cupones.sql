-- Cupones de descuento por tienda ("bono de compra") — PENDIENTE_DUO_CHIC: el admin puede crear
-- uno con código aleatorio o personalizado, de un solo uso o con un límite de usos, y el cliente
-- lo aplica en el carrito antes de pagar.
CREATE TABLE cupones (
    cup_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cup_tnd_id          BIGINT NOT NULL REFERENCES tiendas(tnd_id) ON DELETE CASCADE,
    -- Siempre en mayúsculas (se normaliza al crear/validar) para que "verano20" y "VERANO20"
    -- sean el mismo código — evita duplicados que solo difieren en mayúsculas/minúsculas.
    cup_codigo          VARCHAR(30) NOT NULL,
    cup_tipo_descuento  VARCHAR(10) NOT NULL,
    -- Porcentaje (1-100) o pesos (centavos) según cup_tipo_descuento — nunca los dos a la vez.
    cup_valor           BIGINT NOT NULL CHECK (cup_valor > 0),
    cup_max_usos        INT NOT NULL DEFAULT 1 CHECK (cup_max_usos > 0),
    -- Incrementado con una sola sentencia UPDATE ... WHERE cup_usos_actuales < cup_max_usos
    -- (ver CuponService.canjear) — nunca lee-y-luego-escribe, para que dos canjes simultáneos
    -- justo en el último uso disponible no dejen pasar a los dos.
    cup_usos_actuales   INT NOT NULL DEFAULT 0 CHECK (cup_usos_actuales >= 0),
    cup_activo          BOOLEAN NOT NULL DEFAULT TRUE,
    cup_creado_por      BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    cup_creado_en       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_cup_tipo_descuento CHECK (cup_tipo_descuento IN ('porcentaje', 'fijo')),
    CONSTRAINT chk_cup_valor_porcentaje CHECK (cup_tipo_descuento <> 'porcentaje' OR cup_valor <= 100),
    CONSTRAINT chk_cup_usos_no_supera_max CHECK (cup_usos_actuales <= cup_max_usos),
    CONSTRAINT uq_cup_tnd_codigo UNIQUE (cup_tnd_id, cup_codigo)
);

CREATE INDEX idx_cup_tnd_creado ON cupones (cup_tnd_id, cup_creado_en DESC);

ALTER TABLE cupones ENABLE ROW LEVEL SECURITY;
ALTER TABLE cupones FORCE ROW LEVEL SECURITY;
CREATE POLICY pol_cupones ON cupones USING (cup_tnd_id = fn_current_tnd_id());

ALTER TABLE cupones OWNER TO ecommerce_owner;

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER ON cupones TO calzacaribe_usr;
GRANT SELECT ON cupones TO ecommerce_readonly;
GRANT USAGE, SELECT ON SEQUENCE cupones_cup_id_seq TO calzacaribe_usr;

-- Qué cupón (si alguno) se aplicó a cada pedido — se guarda también el código en texto (snapshot)
-- porque un cupón se puede desactivar o incluso borrar más adelante, y el pedido histórico debe
-- seguir mostrando cuál se usó sin depender de que la fila todavía exista.
ALTER TABLE pedidos ADD COLUMN ped_cupon_id BIGINT REFERENCES cupones(cup_id) ON DELETE SET NULL;
ALTER TABLE pedidos ADD COLUMN ped_cupon_codigo VARCHAR(30);
