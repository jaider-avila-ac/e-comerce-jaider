package jaider.ecommerce.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : "No se pudo procesar la solicitud";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", "No tienes permisos para realizar esta acción"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return conflictResponse(ex);
    }

    // Los INSERT/UPDATE hechos con em.createNativeQuery(...) dentro de un @Service (patrón
    // usado en casi todo el proyecto para columnas con enums de Postgres) no pasan por el
    // proxy de traducción de excepciones de Spring Data — Hibernate lanza su propia
    // ConstraintViolationException en vez de DataIntegrityViolationException, y sin este
    // handler caía en el genérico de 500 en lugar de un 409 con mensaje claro.
    @ExceptionHandler(org.hibernate.exception.ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleHibernateConstraintViolation(
            org.hibernate.exception.ConstraintViolationException ex) {
        return conflictResponse(ex);
    }

    private ResponseEntity<Map<String, String>> conflictResponse(Exception ex) {
        String detail = rootMessage(ex);
        String message = "No se pudo guardar porque el registro esta relacionado con otros datos.";

        if (detail.contains("movimientos_inventario") || detail.contains("mov_var_id")) {
            message = "No se puede eliminar una variante que ya tiene movimientos de inventario. Desactivala o actualiza sus datos sin borrarla.";
        } else if (detail.contains("uq_cp_documento_tnd")) {
            message = "Ese número de documento ya está registrado por otro cliente en esta tienda.";
        } else if (detail.contains("uq_usr_email_tnd") || detail.contains("usuarios_email")) {
            message = "Ese correo ya está registrado en esta tienda.";
        } else if (detail.contains("uq_admin_users_email")) {
            message = "Ya existe un usuario con ese correo en esta tienda.";
        } else if (detail.contains("uq_emp_documento_tnd")) {
            message = "Ese número de documento ya está registrado por otro colaborador en esta tienda.";
        }

        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Error no controlado procesando la solicitud", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Error interno al procesar la solicitud."));
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : "";
    }
}
