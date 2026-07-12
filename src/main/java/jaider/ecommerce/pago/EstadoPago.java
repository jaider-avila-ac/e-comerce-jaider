package jaider.ecommerce.pago;

/** Refleja 1:1 el enum Postgres estado_pago. */
public enum EstadoPago {
    PENDING,
    APPROVED,
    DECLINED,
    VOIDED,
    ERROR
}
