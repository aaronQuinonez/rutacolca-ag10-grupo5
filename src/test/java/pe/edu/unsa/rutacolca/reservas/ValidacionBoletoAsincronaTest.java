package pe.edu.unsa.rutacolca.reservas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.edu.unsa.rutacolca.reservas.adaptadores.AutocolcaMockAdapter;
import pe.edu.unsa.rutacolca.reservas.adaptadores.NotificadorWhatsAppMockAdapter;
import pe.edu.unsa.rutacolca.reservas.adaptadores.PasarelaPagosMockAdapter;
import pe.edu.unsa.rutacolca.reservas.dominio.BoletoTuristico;
import pe.edu.unsa.rutacolca.reservas.dominio.EstadoBoleto;
import pe.edu.unsa.rutacolca.reservas.excepciones.OperacionInvalidaException;
import pe.edu.unsa.rutacolca.reservas.servicio.ProcesadorValidacionBoletos;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de pruebas unitarias automatizadas (JUnit 5) para la validación asíncrona de boletos (Escenario E5).
 * Demuestra:
 * 1. Durante la caída de AUTOCOLCA, la reserva queda en PENDIENTE_VALIDACION y el cobro NO es ejecutado (0 cobros inválidos).
 * 2. Tras simular la recuperación del servicio externo y ejecutar el worker, el boleto pasa a VALIDADO y se habilita el cobro exitosamente en <= 2h.
 * 3. Boletos fraudulentos son rechazados al recuperarse AUTOCOLCA, liberando cupos y ejecutando 0 cobros.
 * 4. La condición de guarda previene cobros accidentales sobre boletos no validados.
 * 5. La expiración por TTL (120 min) libera cupos si AUTOCOLCA no se restablece.
 */
public class ValidacionBoletoAsincronaTest {

    private AutocolcaMockAdapter autocolcaMock;
    private PasarelaPagosMockAdapter pasarelaMock;
    private NotificadorWhatsAppMockAdapter whatsAppMock;
    private ProcesadorValidacionBoletos procesador;

    private final Instant tiempoBase = Instant.parse("2026-07-28T10:00:00Z"); // Temporada alta (Fiestas Patrias)

    @BeforeEach
    void setUp() {
        autocolcaMock = new AutocolcaMockAdapter();
        pasarelaMock = new PasarelaPagosMockAdapter();
        whatsAppMock = new NotificadorWhatsAppMockAdapter();
        procesador = new ProcesadorValidacionBoletos(autocolcaMock, pasarelaMock, whatsAppMock);
    }

    @Test
    @DisplayName("E5 - Caso 1: Durante caída de AUTOCOLCA, reserva en PENDIENTE_VALIDACION y 0 cobros ejecutados")
    void testDuranteCaidaDeAutocolca_ReservaQuedaPendienteYNoSeEjecutaCobro() {
        // GIVEN: El sistema externo de AUTOCOLCA se encuentra caído (timeout / 503)
        autocolcaMock.simularCaida();

        String idReserva = "RES-2026-001";
        String boleto = "BTC-2026-001";
        String titular = "John Doe";
        BigDecimal monto = new BigDecimal("180.00");

        // WHEN: El turista solicita su reserva
        BoletoTuristico reserva = procesador.solicitarReserva(idReserva, boleto, titular, monto, tiempoBase);

        // THEN:
        // 1. La reserva transiciona a estado PENDIENTE_VALIDACION
        assertEquals(EstadoBoleto.PENDIENTE_VALIDACION, reserva.getEstado(),
                "La reserva debe quedar en PENDIENTE_VALIDACION al fallar la comunicación con AUTOCOLCA");

        // 2. El cupo de la salida queda retenido con TTL de 2 horas (120 minutos)
        assertTrue(reserva.isCupoRetenido(), "El cupo debe estar retenido provisionalmente para proteger al turista");
        assertEquals(tiempoBase.plus(Duration.ofHours(2)), reserva.getFechaExpiracionCupo(),
                "El TTL de retención de cupo debe ser exactamente 2 horas");

        // 3. Tarea registrada en la cola outbox de PostgreSQL
        assertEquals(1, procesador.getCantidadTareasPendientesOutbox(),
                "Debe existir exactamente 1 tarea encolada en el outbox");

        // 4. MEDIDA CRÍTICA DE NEGOCIO: Cero (0) cobros a la pasarela de pagos
        assertEquals(0, pasarelaMock.getCantidadCobrosEjecutados(),
                "GARANTÍA ABSOLUTA: No debe haberse ejecutado ningún cobro en la pasarela");
        assertFalse(reserva.isCobroEjecutado(), "El indicador de cobro de la reserva debe ser falso");
        assertEquals(BigDecimal.ZERO, pasarelaMock.getTotalMontoCobrado(), "El monto cobrado debe ser 0.00");

        // 5. Se notifica al turista sobre el estado diferido
        assertTrue(whatsAppMock.getCantidadMensajesEnviados() >= 1, "Debe haberse notificado al turista vía WhatsApp");
    }

    @Test
    @DisplayName("E5 - Caso 2: Tras recuperación de AUTOCOLCA, worker valida boleto y procesa cobro en <= 2 horas")
    void testTrasRecuperacionDeAutocolca_WorkerValidaBoletoYProcesaCobro() {
        // GIVEN: Reserva registrada durante caída de AUTOCOLCA
        autocolcaMock.simularCaida();
        String idReserva = "RES-2026-002";
        String boleto = "BTC-2026-002";
        String titular = "Maria Flores";
        BigDecimal monto = new BigDecimal("120.00");

        BoletoTuristico reserva = procesador.solicitarReserva(idReserva, boleto, titular, monto, tiempoBase);
        assertEquals(EstadoBoleto.PENDIENTE_VALIDACION, reserva.getEstado());
        assertEquals(0, pasarelaMock.getCantidadCobrosEjecutados());

        // Worker ejecuta mientras AUTOCOLCA sigue caído a los 5 minutos -> reintento falla con backoff
        Instant tMas5Min = tiempoBase.plus(Duration.ofMinutes(5));
        procesador.ejecutarWorkerReintentos(tMas5Min);
        assertEquals(1, reserva.getIntentosValidacion(), "Debe haberse registrado 1 reintento con fallo");
        assertEquals(0, pasarelaMock.getCantidadCobrosEjecutados(), "Sigue habiendo 0 cobros mientras esté caído");

        // WHEN: A los 45 minutos (dentro de las 2 horas), AUTOCOLCA restablece su servicio
        Instant tMas45Min = tiempoBase.plus(Duration.ofMinutes(45));
        autocolcaMock.simularRecuperacion();

        // El worker de Spring Boot (@Scheduled con ShedLock) ejecuta el ciclo de reintentos
        int tareasProcesadas = procesador.ejecutarWorkerReintentos(tMas45Min);

        // THEN:
        assertEquals(1, tareasProcesadas, "El worker debió haber procesado la tarea pendiente");
        assertEquals(0, procesador.getCantidadTareasPendientesOutbox(), "La cola outbox debe haber quedado vacía");

        // 1. Estado actualizado a VALIDADO
        assertEquals(EstadoBoleto.VALIDADO, reserva.getEstado(),
                "El boleto debe haber sido VALIDADO por AUTOCOLCA");
        assertEquals("NACIONAL", reserva.getTipoBoleto());
        assertTrue(reserva.isCupoRetenido(), "El cupo debe seguir consolidado");

        // 2. MEDIDA DE NEGOCIO: Cobro ejecutado con éxito recién tras validación afirmativa
        assertEquals(1, pasarelaMock.getCantidadCobrosEjecutados(),
                "Debe haberse ejecutado exactamente 1 cobro tras la validación");
        assertTrue(reserva.isCobroEjecutado(), "El cobro debe estar marcado como ejecutado");
        assertEquals(new BigDecimal("120.00"), pasarelaMock.getTotalMontoCobrado(),
                "El monto cobrado debe corresponder a la reserva");

        // 3. Se verifica cumplimiento temporal: validada y cobrada en 45 minutos (<= 2 horas)
        Duration tiempoTotal = Duration.between(tiempoBase, tMas45Min);
        assertTrue(tiempoTotal.toMinutes() <= 120, "La validación debe haberse cumplido en <= 2 horas");
    }

    @Test
    @DisplayName("E5 - Caso 3: Boleto fraudulento/inválido es rechazado tras recuperación -> 0 cobros y cupo liberado")
    void testBoletoInvalidoTrasRecuperacion_LiberaCupoYNoEjecutaCobro() {
        // GIVEN: Boleto falso "BTC-INVENTADO-999" registrado durante la caída
        autocolcaMock.simularCaida();
        String idReserva = "RES-2026-FRAUDE";
        String boletoFalso = "BTC-INVENTADO-999";
        String titular = "Estafador Turístico";
        BigDecimal monto = new BigDecimal("350.00");

        BoletoTuristico reserva = procesador.solicitarReserva(idReserva, boletoFalso, titular, monto, tiempoBase);
        assertTrue(reserva.isCupoRetenido(), "El cupo se retiene preventivamente");
        assertEquals(0, pasarelaMock.getCantidadCobrosEjecutados());

        // WHEN: AUTOCOLCA se recupera y el worker procesa la tarea
        autocolcaMock.simularRecuperacion();
        Instant tMas30Min = tiempoBase.plus(Duration.ofMinutes(30));
        procesador.ejecutarWorkerReintentos(tMas30Min);

        // THEN:
        // 1. Estado pasa a RECHAZADO
        assertEquals(EstadoBoleto.RECHAZADO, reserva.getEstado(),
                "El boleto falso debe ser RECHAZADO por AUTOCOLCA");
        assertNotNull(reserva.getMotivoRechazo());

        // 2. El cupo es liberado inmediatamente para otros turistas
        assertFalse(reserva.isCupoRetenido(), "El cupo debe liberarse automáticamente del inventario");

        // 3. MEDIDA DE NEGOCIO INQUEBRANTABLE: Cero cobros al boleto inválido
        assertEquals(0, pasarelaMock.getCantidadCobrosEjecutados(),
                "REGLA DE ORO: Exactamente 0 cobros ejecutados a boletos inválidos");
        assertFalse(reserva.isCobroEjecutado());
        assertEquals(BigDecimal.ZERO, pasarelaMock.getTotalMontoCobrado());
    }

    @Test
    @DisplayName("E5 - Caso 4: Condición de guarda impide cobro si el estado != VALIDADO")
    void testCondicionDeGuardaImpideCobroEnEstadoNoValidado() {
        BoletoTuristico reservaPendiente = new BoletoTuristico(
                "RES-TEST-GUARD", "BTC-2026-001", "Pedro Perez", new BigDecimal("100.00"), tiempoBase
        );
        reservaPendiente.retenerCupoTemporal(Duration.ofHours(2));

        // Intento directo de cobro sobre estado PENDIENTE_VALIDACION
        OperacionInvalidaException ex = assertThrows(OperacionInvalidaException.class, () -> {
            procesador.procesarCobro(reservaPendiente);
        }, "Debe lanzar excepción al intentar cobrar sin confirmación");

        assertTrue(ex.getMessage().contains("VIOLACIÓN DE REGLA DE NEGOCIO"),
                "El mensaje debe señalar la violación de la regla de 0 cobros a boletos no validados");
        assertEquals(0, pasarelaMock.getCantidadCobrosEjecutados(), "No debe existir ningún cargo ejecutado");
    }

    @Test
    @DisplayName("E5 - Caso 5: Expiración por TTL (120 minutos) libera cupo si AUTOCOLCA nunca responde")
    void testExpiracionPorTTL_LiberaCupoTrasDosHorasSinRespuesta() {
        // GIVEN: AUTOCOLCA sufre una caída prolongada que supera las 2 horas
        autocolcaMock.simularCaida();
        BoletoTuristico reserva = procesador.solicitarReserva(
                "RES-EXPIRA", "BTC-2026-003", "Carlos Quispe", new BigDecimal("150.00"), tiempoBase
        );
        assertTrue(reserva.isCupoRetenido());

        // WHEN: El worker corre a las 2 horas y 1 minuto (T + 121 minutos)
        Instant tMas121Min = tiempoBase.plus(Duration.ofMinutes(121));
        procesador.ejecutarWorkerReintentos(tMas121Min);

        // THEN:
        // La reserva transiciona a EXPIRADO, el cupo se devuelve al inventario y NUNCA se cobra
        assertEquals(EstadoBoleto.EXPIRADO, reserva.getEstado(),
                "Debe marcarse como EXPIRADO al superar la ventana de 2 horas");
        assertFalse(reserva.isCupoRetenido(), "El cupo debe haberse liberado");
        assertEquals(0, pasarelaMock.getCantidadCobrosEjecutados(), "Cero cobros efectuados");
    }
}
