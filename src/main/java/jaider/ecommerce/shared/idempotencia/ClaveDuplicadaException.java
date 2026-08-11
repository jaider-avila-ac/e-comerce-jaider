package jaider.ecommerce.shared.idempotencia;

/** Señal interna: ya existe una fila de idempotencia para esta (tienda, usuario, operación,
 *  clave) — el INSERT chocó contra el constraint único. No es un error de negocio, solo le dice
 *  a IdempotenciaGuard que debe buscar la fila existente en vez de haber creado una nueva. */
class ClaveDuplicadaException extends RuntimeException {
}