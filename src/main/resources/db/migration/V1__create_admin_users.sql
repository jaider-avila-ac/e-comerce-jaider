CREATE TABLE IF NOT EXISTS admin_users (
    id        BIGSERIAL    PRIMARY KEY,
    email     VARCHAR(255) NOT NULL UNIQUE,
    password  VARCHAR(255) NOT NULL,
    nombre    VARCHAR(100) NOT NULL,
    activo    BOOLEAN      NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMP    NOT NULL DEFAULT NOW()
);
