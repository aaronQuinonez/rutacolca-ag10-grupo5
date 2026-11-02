package pe.edu.unsa.rutacolca.reservas.excepciones;

/**
 * Excepción lanzada cuando la API de AUTOCOLCA no está disponible
 * (por timeout, error 502/503/504 o falla de red).
 */
public class AutocolcaUnavailableException extends Exception {
    public AutocolcaUnavailableException(String message) {
        super(message);
    }

    public AutocolcaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
