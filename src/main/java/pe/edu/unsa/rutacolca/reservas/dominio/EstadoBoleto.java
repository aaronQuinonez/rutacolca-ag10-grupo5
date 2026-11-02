package pe.edu.unsa.rutacolca.reservas.dominio;

/**
 * Estados posibles del ciclo de vida del boleto turístico y su reserva asociada.
 */
public enum EstadoBoleto {
    /**
     * Solicitud recibida mientras AUTOCOLCA estaba inaccesible.
     * Cupo retenido temporalmente (TTL 2 horas). Cobro pospuesto.
     */
    PENDIENTE_VALIDACION,

    /**
     * Boleto verificado exitosamente con AUTOCOLCA.
     * Cupo confirmado y cobro monetario habilitado/procesado.
     */
    VALIDADO,

    /**
     * Boleto rechazado por AUTOCOLCA (inexistente, duplicado o caducado).
     * Cupo liberado inmediatamente y cobro bloqueado permanentemente.
     */
    RECHAZADO,

    /**
     * La ventana de tolerancia (TTL de 2 horas) expiró sin que AUTOCOLCA respondiera.
     * Cupo liberado automáticamente al inventario.
     */
    EXPIRADO
}
