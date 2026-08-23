package jaider.ecommerce.catalogo.pregunta;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Pregunta de un cliente sobre un producto, con su respuesta (si ya la hay). A propósito NO se
 * borra en cascada silenciosa desde la app — si el producto se borra, la pregunta se va con él
 * (ON DELETE CASCADE en la BD, ver migración): a diferencia de pedido_items, esto no es un dato
 * contable que deba sobrevivir, es contenido "del" producto. El admin puede borrarla igual
 * (moderación) sin que eso implique borrar el producto — por eso "eliminada" es un campo aparte,
 * no un DELETE real: preg_eliminada la oculta de la tienda pero el admin la sigue viendo.
 */
@Entity
@Table(name = "producto_preguntas")
@Getter
@Setter
public class Pregunta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "preg_id")
    private Long id;

    @Column(name = "preg_tnd_id", nullable = false, updatable = false)
    private Long tndId;

    @Column(name = "preg_prd_id", updatable = false)
    private Long prdId;

    @Column(name = "preg_usr_id", nullable = false, updatable = false)
    private Long usrId;

    @Column(name = "preg_texto", nullable = false)
    private String texto;

    @Column(name = "preg_editada", nullable = false)
    private boolean editada = false;

    @Column(name = "preg_eliminada", nullable = false)
    private boolean eliminada = false;

    // 'cliente' | 'admin' — quién la borró, para que el panel distinga "la borró el que
    // preguntó" de "la moderó el staff" en vez de mostrar solo un genérico "eliminada".
    @Column(name = "preg_eliminada_por", length = 20)
    private String eliminadaPor;

    @Column(name = "preg_eliminada_en")
    private OffsetDateTime eliminadaEn;

    @Column(name = "preg_respuesta_texto")
    private String respuestaTexto;

    @Column(name = "preg_respuesta_admin_id")
    private Long respuestaAdminId;

    @Column(name = "preg_respuesta_editada", nullable = false)
    private boolean respuestaEditada = false;

    @Column(name = "preg_respondida_en")
    private OffsetDateTime respondidaEn;

    @Column(name = "preg_creado_en", insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    @Column(name = "preg_actualizado_en", insertable = false, updatable = false)
    private OffsetDateTime actualizadoEn;
}
