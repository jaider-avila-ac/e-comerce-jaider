-- La "guía de tallas" por producto ya existe en el código (Producto.guiaTallasUrl) y en
-- producción desde antes, pero esta columna nunca quedó documentada acá ni aplicada en la BD
-- local de desarrollo — ProductoServiceTest/EmpaqueServiceTest/PaqueteCalculoServiceTest fallaban
-- en local por columna inexistente. Mismo nombre/tipo que ya usa producción.
ALTER TABLE productos ADD COLUMN IF NOT EXISTS prd_guia_tallas_url text;
