package pe.edu.unsa.rutacolca.reservas.servicio;

import pe.edu.unsa.rutacolca.reservas.dominio.BoletoTuristico;
import pe.edu.unsa.rutacolca.reservas.dominio.EstadoBoleto;
import pe.edu.unsa.rutacolca.reservas.dominio.ResultadoValidacion;
import pe.edu.unsa.rutacolca.reservas.excepciones.AutocolcaUnavailableException;
import pe.edu.unsa.rutacolca.reservas.excepciones.OperacionInvalidaException;
import pe.edu.unsa.rutacolca.reservas.puertos.NotificadorWhatsApp;
import pe.edu.unsa.rutacolca.reservas.puertos.PasarelaPagos;
import pe.edu.unsa.rutacolca.reservas.puertos.ValidadorBoleto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de orquestación de reservas y validación asíncrona de boletos turísticos.
 * Implementa el patrón Transactional Outbox y las tácticas de recuperación del ADR-005.
 */
public class ProcesadorValidacionBoletos {

    private final ValidadorBoleto validadorBoleto;
    private final PasarelaPagos pasarelaPagos;
    private final NotificadorWhatsApp notificadorWhatsApp;

    // Simulación en memoria de la tabla relacional de reservas y outbox en PostgreSQL 16
    private final Map<String, BoletoTuristico> tablaReservas = new ConcurrentHashMap<>();
    private final List<BoletoTuristico> colaOutboxPendientes = Collections.synchronizedList(new ArrayList<>());

    public ProcesadorValidacionBoletos(ValidadorBoleto validadorBoleto,
                                       PasarelaPagos pasarelaPagos,
                                       NotificadorWhatsApp notificadorWhatsApp) {
        this.validadorBoleto = validadorBoleto;
        this.pasarelaPagos = pasarelaPagos;
        this.notificadorWhatsApp = notificadorWhatsApp;
    }

    /**
     * Paso 1-5: Procesa la solicitud inicial de reserva del turista.
     * Si AUTOCOLCA está disponible, valida sincrónicamente.
     * Si AUTOCOLCA sufre timeout o caída, activa el flujo diferido (E5):
     * retiene cupo temporal (TTL 2h), encola outbox y POSPONE el cobro (0 cobros a no validados).
     */
    public BoletoTuristico solicitarReserva(String idReserva,
                                            String numeroBoleto,
                                            String titular,
                                            BigDecimal monto,
                                            Instant tiempoActual) {
        BoletoTuristico boleto = new BoletoTuristico(idReserva, numeroBoleto, titular, monto, tiempoActual);
        tablaReservas.put(idReserva, boleto);

        try {
            // Intento síncrono rápido (con timeout acotado de 2.5s)
            ResultadoValidacion resultado = validadorBoleto.validar(numeroBoleto);

            if (resultado.valido()) {
                boleto.retenerCupoTemporal(BoletoTuristico.TTL_DEFECTO);
                boleto.confirmarValidacion(resultado.tipoBoleto());
                // Cobro inmediato seguro porque ya fue VALIDADO
                procesarCobro(boleto);
                notificadorWhatsApp.enviarMensaje(titular, "Reserva " + idReserva + " confirmada y pagada.");
            } else {
                boleto.rechazar(resultado.mensajeRespuesta());
                // NUNCA se cobra si el boleto es rechazado
            }

        } catch (AutocolcaUnavailableException e) {
            // DEGRADACIÓN RESILIENTE (Táctica: Orquestación diferida / Outbox):
            // 1. Retener temporalmente el cupo de la salida por 2 horas (TTL)
            boleto.retenerCupoTemporal(BoletoTuristico.TTL_DEFECTO);

            // 2. Estado transicional PENDIENTE_VALIDACION
            // 3. Encolar en la tabla outbox transaccional para reintento por worker
            colaOutboxPendientes.add(boleto);

            // 4. CONDICIÓN CRÍTICA: NO invocar pasarela de pagos.
            // 5. Notificar recepción diferida al turista
            notificadorWhatsApp.enviarMensaje(titular,
                    "Solicitud " + idReserva + " registrada. AUTOCOLCA en mantenimiento. Boleto en validación diferida (TTL 2h).");
        }

        return boleto;
    }

    /**
     * Paso 6-10: Ejecución periódica del Worker en segundo plano (simula @Scheduled con ShedLock).
     * Evalúa las tareas pendientes en la cola outbox de PostgreSQL.
     */
    public int ejecutarWorkerReintentos(Instant tiempoActual) {
        List<BoletoTuristico> tareasAProcesar;
        synchronized (colaOutboxPendientes) {
            tareasAProcesar = new ArrayList<>(colaOutboxPendientes);
        }

        int procesadas = 0;

        for (BoletoTuristico boleto : tareasAProcesar) {
            if (boleto.getEstado() != EstadoBoleto.PENDIENTE_VALIDACION) {
                colaOutboxPendientes.remove(boleto);
                continue;
            }

            // Verificar si expira el TTL de 2 horas (Punto de Sensibilidad 2)
            if (tiempoActual.isAfter(boleto.getFechaExpiracionCupo())) {
                boleto.expirar();
                colaOutboxPendientes.remove(boleto);
                notificadorWhatsApp.enviarMensaje(boleto.getTitular(),
                        "Reserva " + boleto.getIdReserva() + " expirada: AUTOCOLCA no restableció servicio en 2 horas.");
                continue;
            }

            // Verificar si corresponde el reintento según el intervalo de backoff
            if (tiempoActual.isBefore(boleto.getProximoReintento())) {
                continue; // Esperar siguiente ventana
            }

            try {
                // Reintento contra el adaptador de AUTOCOLCA
                ResultadoValidacion resultado = validadorBoleto.validar(boleto.getNumeroBoleto());

                if (resultado.valido()) {
                    // Boleto validado exitosamente tras restablecimiento de AUTOCOLCA
                    boleto.confirmarValidacion(resultado.tipoBoleto());

                    // CONDICIÓN DE GUARDA ESTRICTA ANTES DE COBRAR:
                    procesarCobro(boleto);

                    notificadorWhatsApp.enviarMensaje(boleto.getTitular(),
                            "¡Boleto validado! Reserva " + boleto.getIdReserva() + " confirmada y pagada.");
                } else {
                    // Boleto fraudulento, inexistente o rechazado por AUTOCOLCA
                    boleto.rechazar(resultado.mensajeRespuesta());
                    // El cupo se libera de inmediato y NO se cobra (0 cobros a boletos inválidos)
                    notificadorWhatsApp.enviarMensaje(boleto.getTitular(),
                            "Reserva " + boleto.getIdReserva() + " rechazada: " + resultado.mensajeRespuesta());
                }

                // Tarea resuelta: remover de outbox
                colaOutboxPendientes.remove(boleto);
                procesadas++;

            } catch (AutocolcaUnavailableException e) {
                // AUTOCOLCA sigue caído: aplicar táctica de Backoff Exponencial
                int intentos = boleto.getIntentosValidacion();
                long segundosEspera = (long) Math.min(30 * Math.pow(2, intentos), 900); // 30s, 60s, 120s... max 15m
                boleto.registrarFalloReintento(Duration.ofSeconds(segundosEspera), tiempoActual);
            }
        }

        return procesadas;
    }

    /**
     * Condición de guarda inquebrantable de la arquitectura:
     * NUNCA se procesa un cobro si el estado no es formalmente VALIDADO.
     */
    public void procesarCobro(BoletoTuristico boleto) {
        if (boleto.getEstado() != EstadoBoleto.VALIDADO) {
            throw new OperacionInvalidaException(
                    "VIOLACIÓN DE REGLA DE NEGOCIO: Intento de cobro sobre boleto en estado " + boleto.getEstado()
                            + ". Se exige estado VALIDADO para garantizar 0 cobros a boletos no confirmados."
            );
        }

        if (!boleto.isCupoRetenido()) {
            throw new OperacionInvalidaException("PROHIBIDO COBRAR: El cupo no está retenido para la reserva " + boleto.getIdReserva());
        }

        if (boleto.isCobroEjecutado()) {
            return; // Idempotencia de pago
        }

        boolean exito = pasarelaPagos.procesarCobro(boleto.getIdReserva(), boleto.getMontoReserva());
        if (exito) {
            boleto.registrarCobroExitoso();
        }
    }

    public BoletoTuristico obtenerReserva(String idReserva) {
        return tablaReservas.get(idReserva);
    }

    public int getCantidadTareasPendientesOutbox() {
        return colaOutboxPendientes.size();
    }
}
