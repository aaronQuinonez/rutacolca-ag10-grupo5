package pe.edu.unsa.rutacolca.reservas.puertos;

import java.math.BigDecimal;

/**
 * Puerto de salida para el procesamiento de cobros monetarios a través de la pasarela de pagos.
 */
public interface PasarelaPagos {

    /**
     * Procesa el cobro del monto de la reserva en la tarjeta o billetera del turista.
     *
     * @param idReserva Identificador único de la reserva.
     * @param monto Monto monetario a debitar.
     * @return true si la transacción fue aprobada, false en caso contrario.
     */
    boolean procesarCobro(String idReserva, BigDecimal monto);

    /**
     * Retorna el número de cobros efectivamente ejecutados (para verificación de la regla: 0 cobros a inválidos).
     */
    int getCantidadCobrosEjecutados();

    /**
     * Retorna la suma total de dinero debitada.
     */
    BigDecimal getTotalMontoCobrado();
}
