package pe.edu.unsa.rutacolca.reservas.excepciones;

/**
 * Excepción lanzada cuando se intenta realizar una operación que viola
 * una regla de negocio o condición de guarda crítica (p. ej., intentar cobrar
 * una reserva cuyo boleto no ha sido formalmente VALIDADO por AUTOCOLCA).
 */
public class OperacionInvalidaException extends RuntimeException {
    public OperacionInvalidaException(String message) {
        super(message);
    }
}
