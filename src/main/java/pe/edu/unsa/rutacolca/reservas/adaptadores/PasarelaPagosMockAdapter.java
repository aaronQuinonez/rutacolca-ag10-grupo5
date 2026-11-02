package pe.edu.unsa.rutacolca.reservas.adaptadores;

import pe.edu.unsa.rutacolca.reservas.puertos.PasarelaPagos;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adaptador simulado en memoria para la Pasarela de Pagos.
 * Permite registrar y auditar cada intento de cobro para comprobar la regla "0 cobros a inválidos".
 */
public class PasarelaPagosMockAdapter implements PasarelaPagos {

    private final AtomicInteger contadorCobros = new AtomicInteger(0);
    private BigDecimal totalCobrado = BigDecimal.ZERO;
    private final List<String> historialCobros = Collections.synchronizedList(new ArrayList<>());

    @Override
    public synchronized boolean procesarCobro(String idReserva, BigDecimal monto) {
        contadorCobros.incrementAndGet();
        totalCobrado = totalCobrado.add(monto);
        historialCobros.add("Cobro exitoso: Reserva=" + idReserva + ", Monto=S/ " + monto);
        return true;
    }

    @Override
    public int getCantidadCobrosEjecutados() {
        return contadorCobros.get();
    }

    @Override
    public synchronized BigDecimal getTotalMontoCobrado() {
        return totalCobrado;
    }

    public List<String> getHistorialCobros() {
        return Collections.unmodifiableList(historialCobros);
    }
}
