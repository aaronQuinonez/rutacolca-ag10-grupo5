package pe.edu.unsa.rutacolca.reservas.dominio;

/**
 * Resultado inmutable retornado por el puerto de validación de AUTOCOLCA.
 */
public record ResultadoValidacion(
        boolean valido,
        String numeroBoleto,
        String tipoBoleto,
        String titular,
        String mensajeRespuesta
) {
    public static ResultadoValidacion aprobado(String numeroBoleto, String tipoBoleto, String titular) {
        return new ResultadoValidacion(true, numeroBoleto, tipoBoleto, titular, "Boleto válido y vigente ante AUTOCOLCA");
    }

    public static ResultadoValidacion rechazado(String numeroBoleto, String motivo) {
        return new ResultadoValidacion(false, numeroBoleto, null, null, motivo);
    }
}
