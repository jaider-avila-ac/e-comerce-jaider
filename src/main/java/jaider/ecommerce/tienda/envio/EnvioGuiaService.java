package jaider.ecommerce.tienda.envio;

import jaider.ecommerce.pedido.Pedido;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Preparar envío" + generación de guía REAL para un pedido ya pagado — PLAN_INTEGRACION_ENVIA.md,
 * Fase 4. A diferencia de {@link EnvioCotizacionService} (Fase 3, cotización gratis en el
 * checkout), {@link #generarGuia} SÍ cobra de la cuenta de la tienda en Envia — nunca se llama
 * sin que el admin lo confirme explícitamente desde la pantalla de preparación.
 *
 * Reutiliza las columnas de seguimiento que ya existían (Pedido.transportadora/codigoRastreo/
 * linkSeguimiento — hasta ahora las llenaba el admin a mano); se llenan solas al generar la guía.
 *
 * IMPORTANTE (corrección de auditoría, 2026-09-01, tercera vuelta): esta clase ya NO tiene
 * ningún método @Transactional — toda la lectura/escritura de nuestra propia BD vive en
 * {@link EnvioGuiaTransaccionesService} (bean separado, cada paso en su propia transacción). Acá
 * solo se orquesta el flujo y ocurren las llamadas HTTP reales (geocoding, Envia) — nunca dentro
 * de una transacción de BD abierta. Ver el javadoc de esa clase para el porqué del double-charge
 * que esto corrige.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvioGuiaService {

    /** Reintentos de la escritura mínima crítica tras un cobro real confirmado por Envia. */
    private static final int REINTENTOS_CONFIRMAR = 3;
    private static final long ESPERA_ENTRE_REINTENTOS_MS = 500;

    private final EnvioGuiaTransaccionesService transacciones;
    private final EnvioCotizacionService cotizacionService;
    private final EnviaGeocodesClient geocodesClient;
    private final EnviaRateClient rateClient;
    private final EnviaLabelClient labelClient;

    public PrepararEnvioResponse preparar(Long tndId, Long pedidoId) {
        EnvioGuiaTransaccionesService.DatosGuia datos = transacciones.cargarDatosParaGuia(tndId, pedidoId);
        Pedido pedido = datos.pedido();

        // Corrección de auditoría (2026-09-01, tercera vuelta): si el pedido YA tiene una guía
        // real generada, no hace falta volver a geocodificar ni cotizar con Envia para mostrar
        // sus datos — antes, una caída de Envia, credenciales vencidas o un origen dañado
        // impedían al admin consultar en el panel una guía que YA está guardada.
        if (pedido.getEnviaShipmentId() != null) {
            return new PrepararEnvioResponse(datos.paquetes(), List.of(), true,
                    pedido.getTransportadora(), pedido.getCodigoRastreo(), pedido.getEnviaGuiaUrl(),
                    pedido.getEnviaShipmentId(), pedido.getEnviaCostoRealCentavos());
        }

        GeocodeResultado origenGeo = geocodesClient.resolver(datos.origen().codigoPostal());
        GeocodeResultado destinoGeo = geocodesClient.resolver(datos.destino().codigoPostal());

        List<CotizacionCarrier> cotizaciones = new ArrayList<>();
        for (String carrier : ordenTransportadoras(tndId)) {
            Optional<CotizacionCarrier> cot = rateClient.cotizar(datos.host(), datos.creds().apiToken(), carrier,
                    datos.origen(), origenGeo, datos.destino(), destinoGeo, datos.paquetes(), datos.declaradoCop());
            cot.ifPresent(cotizaciones::add);
        }

        return new PrepararEnvioResponse(datos.paquetes(), cotizaciones, false,
                pedido.getTransportadora(), pedido.getCodigoRastreo(), pedido.getEnviaGuiaUrl(),
                null, null);
    }

    /** Crea el envío REAL y lo cobra de la cuenta de Envia de la tienda — protegido contra doble
     *  clic con una reserva atómica que ahora commitea en su PROPIA transacción antes de llamar a
     *  Envia (ver {@link EnvioGuiaTransaccionesService}). Un pedido con guía ya generada no puede
     *  volver a generar otra sin antes cancelarla (cancelación todavía no está construida — Fase
     *  futura). */
    public GuiaGenerada generarGuia(Long tndId, Long pedidoId, GenerarGuiaRequest req, Long adminId) {
        if (req.carrier() == null || req.carrier().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes indicar la transportadora");
        }
        if (!TransportadoraService.CARRIERS_VALIDOS.contains(req.carrier().trim().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transportadora inválida: " + req.carrier() + " (válidas: " + TransportadoraService.CARRIERS_VALIDOS + ")");
        }

        EnvioGuiaTransaccionesService.DatosGuia datos = transacciones.cargarDatosParaGuia(tndId, pedidoId);
        Pedido pedido = datos.pedido();

        int reservado = transacciones.reservar(pedido.getId());
        if (reservado == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este pedido ya tiene una guía generada o en proceso — no se puede generar otra");
        }

        GeocodeResultado origenGeo = geocodesClient.resolver(datos.origen().codigoPostal());
        GeocodeResultado destinoGeo = geocodesClient.resolver(datos.destino().codigoPostal());

        GuiaGenerada guia;
        try {
            // Llamada real — ocurre completamente FUERA de cualquier transacción de BD abierta.
            guia = labelClient.generar(datos.host(), datos.creds().apiToken(), req.carrier(), req.servicio(),
                    datos.origen(), origenGeo, datos.destino(), destinoGeo, datos.paquetes(), datos.declaradoCop(),
                    pedido.getNumero());
            log.info("[EnvioGuia] Envia confirmó el envío — pedido={} tenant={} carrier={} shipmentId={} tracking={}",
                    pedidoId, tndId, guia.carrier(), guia.shipmentId(), guia.trackingNumber());
        } catch (Exception e) {
            // Envia NO confirmó ningún envío — libera la reserva para que este pedido pueda
            // reintentar limpio.
            transacciones.liberarReserva(pedido.getId());
            log.error("[EnvioGuia] falló generar guía real para pedido {} (tenant {}): {}", pedidoId, tndId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Envia no pudo generar la guía: " + e.getMessage());
        }

        // A partir de acá Envia YA cobró de verdad — confirmarShipmentIdTx() se reintenta varias
        // veces (cada intento en su propia transacción REQUIRES_NEW) antes de darse por vencido,
        // porque un fallo transitorio de BD en este punto NO puede tratarse como "no pasó nada".
        for (int intento = 1; intento <= REINTENTOS_CONFIRMAR; intento++) {
            try {
                int filas = transacciones.confirmarShipmentIdTx(pedido.getId(), guia.shipmentId());
                if (filas == 0) {
                    log.error("[EnvioGuia][CRÍTICO] pedido={} tenant={} — Envia YA CONFIRMÓ un envío real " +
                                    "(shipmentId={} tracking={} costo={}) pero la fila no estaba en RESERVANDO " +
                                    "al confirmar — revisar manualmente si esta guía quedó registrada.",
                            pedidoId, tndId, guia.shipmentId(), guia.trackingNumber(), guia.totalPriceCop());
                }
                break;
            } catch (Exception e) {
                log.error("[EnvioGuia] intento {}/{} fallido al confirmar shipmentId real para pedido={} tenant={}: {}",
                        intento, REINTENTOS_CONFIRMAR, pedidoId, tndId, e.getMessage());
                if (intento == REINTENTOS_CONFIRMAR) {
                    log.error("[EnvioGuia][CRÍTICO — RECONCILIAR A MANO] pedido={} tenant={}: Envia YA CONFIRMÓ y " +
                                    "COBRÓ un envío real que NO se pudo persistir tras {} intentos — " +
                                    "carrier={} servicio={} shipmentId={} tracking={} trackUrl={} labelUrl={} costoRealCop={}",
                            pedidoId, tndId, REINTENTOS_CONFIRMAR, guia.carrier(), guia.servicio(),
                            guia.shipmentId(), guia.trackingNumber(), guia.trackUrl(), guia.labelUrl(), guia.totalPriceCop());
                } else {
                    dormir(ESPERA_ENTRE_REINTENTOS_MS);
                }
            }
        }

        transacciones.registrarDetalleGuia(pedido.getId(), tndId, guia, datos.tienda().getEnviaAmbiente());

        log.info("[EnvioGuia] guía real generada — pedido={} tenant={} carrier={} tracking={} admin={}",
                pedidoId, tndId, guia.carrier(), guia.trackingNumber(), adminId);
        return guia;
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> ordenTransportadoras(Long tndId) {
        return cotizacionService.ordenTransportadoras(tndId);
    }
}
