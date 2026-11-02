package pe.edu.unsa.rutacolca.reservas.adaptadores;

import pe.edu.unsa.rutacolca.reservas.dominio.ResultadoValidacion;
import pe.edu.unsa.rutacolca.reservas.excepciones.AutocolcaUnavailableException;
import pe.edu.unsa.rutacolca.reservas.puertos.ValidadorBoleto;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adaptador simulado en memoria del sistema externo de AUTOCOLCA.
 * Permite simular caídas, timeouts y recuperaciones del servicio para pruebas de arquitectura.
 */
public class AutocolcaMockAdapter implements ValidadorBoleto {

    private volatile boolean servicioDisponible = true;
    private final AtomicInteger contadorLlamadas = new AtomicInteger(0);
    private final AtomicInteger contadorFallosSimulados = new AtomicInteger(0);
    private final AtomicInteger contadorValidacionesExitosas = new AtomicInteger(0);

    // Registro de boletos válidos conocidos en el sistema de AUTOCOLCA
    private final Map<String, ResultadoValidacion> boletosEmitidos = new ConcurrentHashMap<>();

    public AutocolcaMockAdapter() {
        // Inicialización con algunos boletos válidos de prueba
        registrarBoletoEmitido("BTC-2026-001", "EXTRANJERO", "John Doe");
        registrarBoletoEmitido("BTC-2026-002", "NACIONAL", "Maria Flores");
        registrarBoletoEmitido("BTC-2026-003", "REGIONAL", "Carlos Quispe");
    }

    public void registrarBoletoEmitido(String numeroBoleto, String tipo, String titular) {
        boletosEmitidos.put(numeroBoleto, ResultadoValidacion.aprobado(numeroBoleto, tipo, titular));
    }

    /**
     * Simula la caída o timeout de la API externa de AUTOCOLCA.
     */
    public void simularCaida() {
        this.servicioDisponible = false;
    }

    /**
     * Simula el restablecimiento del servicio de AUTOCOLCA tras una interrupción.
     */
    public void simularRecuperacion() {
        this.servicioDisponible = true;
    }

    @Override
    public ResultadoValidacion validar(String numeroBoleto) throws AutocolcaUnavailableException {
        contadorLlamadas.incrementAndGet();

        // Si el servicio externo está caído o en timeout, lanzamos la excepción
        if (!servicioDisponible) {
            contadorFallosSimulados.incrementAndGet();
            throw new AutocolcaUnavailableException(
                    "Error de conexión con AUTOCOLCA: Read timed out tras 2500 ms (HTTP 504 Gateway Timeout)"
            );
        }

        // Si el servicio está disponible, evaluamos el boleto en el registro
        if (boletosEmitidos.containsKey(numeroBoleto)) {
            contadorValidacionesExitosas.incrementAndGet();
            return boletosEmitidos.get(numeroBoleto);
        } else {
            return ResultadoValidacion.rechazado(numeroBoleto, "Boleto inexistente o no registrado en el padrón de AUTOCOLCA");
        }
    }

    public boolean isServicioDisponible() { return servicioDisponible; }
    public int getContadorLlamadas() { return contadorLlamadas.get(); }
    public int getContadorFallosSimulados() { return contadorFallosSimulados.get(); }
    public int getContadorValidacionesExitosas() { return contadorValidacionesExitosas.get(); }
}
