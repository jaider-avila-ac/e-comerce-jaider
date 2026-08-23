package jaider.ecommerce.catalogo.pregunta;

import java.time.OffsetDateTime;

public record PreguntaEdicionResponse(String campo, String textoAnterior, OffsetDateTime editadoEn) {}
