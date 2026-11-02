package pe.edu.unsa.rutacolca.reservas.dominio;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Entidad de dominio que representa el boleto turístico y el estado de su reserva.
 * Encapsula las reglas de retención de cupo, TTL y validación según el Escenario E5.
 */
public class BoletoTuristico {

    public static final Duration TTL_DEFECTO = Duration.ofHours(2); // 120 minutos

    private final String idReserva;
    private final String numeroBoleto;
    private final String titular;
    private final BigDecimal montoReserva;
    private final Instant fechaCreacion;

    private String tipoBoleto;
    private EstadoBoleto estado;
    private Instant fechaExpiracionCupo;
    private boolean cupoRetenido;
    private boolean cobroEjecutado;
    private int intentosValidacion;
    private Instant proximoReintento;
    private String motivoRechazo;

    public BoletoTuristico(String idReserva, String numeroBoleto, String titular, BigDecimal montoReserva, Instant fechaCreacion) {
        this.idReserva = Objects.requireNonNull(idReserva, "idReserva no puede ser nulo");
        this.numeroBoleto = Objects.requireNonNull(numeroBoleto, "numeroBoleto no puede ser nulo");
        this.titular = Objects.requireNonNull(titular, "titular no puede ser nulo");
        this.montoReserva = Objects.requireNonNull(montoReserva, "montoReserva no puede ser nulo");
        this.fechaCreacion = Objects.requireNonNull(fechaCreacion, "fechaCreacion no puede ser nulo");

        this.estado = EstadoBoleto.PENDIENTE_VALIDACION;
        this.cupoRetenido = false;
        this.cobroEjecutado = false;
        this.intentosValidacion = 0;
        this.proximoReintento = fechaCreacion;
    }

    /**
     * Retiene temporalmente el cupo de la salida asignando un tiempo de vida (TTL).
     */
    public void retenerCupoTemporal(Duration ttl) {
        this.cupoRetenido = true;
        this.fechaExpiracionCupo = this.fechaCreacion.plus(ttl);
    }

    /**
     * Libera el cupo provisional cuando el boleto es rechazado o expira el TTL.
     */
    public void liberarCupo() {
        this.cupoRetenido = false;
    }

    /**
     * Transiciona el estado a VALIDADO al recibir confirmación de AUTOCOLCA.
     */
    public void confirmarValidacion(String tipoBoleto) {
        this.estado = EstadoBoleto.VALIDADO;
        this.tipoBoleto = tipoBoleto;
        this.motivoRechazo = null;
    }

    /**
     * Transiciona el estado a RECHAZADO y libera el cupo si el boleto es inválido.
     */
    public void rechazar(String motivo) {
        this.estado = EstadoBoleto.RECHAZADO;
        this.motivoRechazo = motivo;
        liberarCupo();
    }

    /**
     * Transiciona el estado a EXPIRADO si se superó la ventana de 2 horas sin respuesta.
     */
    public void expirar() {
        this.estado = EstadoBoleto.EXPIRADO;
        liberarCupo();
    }

    /**
     * Registra un reintento fallido y programa la siguiente ejecución con backoff.
     */
    public void registrarFalloReintento(Duration intervaloBackoff, Instant momentoActual) {
        this.intentosValidacion++;
        this.proximoReintento = momentoActual.plus(intervaloBackoff);
    }

    /**
     * Marca el cobro como ejecutado exitosamente.
     */
    public void registrarCobroExitoso() {
        this.cobroEjecutado = true;
    }

    /**
     * Condición de guarda: verifica si el boleto está en condiciones válidas para cobro.
     */
    public boolean esAptoParaCobro() {
        return this.estado == EstadoBoleto.VALIDADO && this.cupoRetenido && !this.cobroEjecutado;
    }

    // Getters
    public String getIdReserva() { return idReserva; }
    public String getNumeroBoleto() { return numeroBoleto; }
    public String getTitular() { return titular; }
    public BigDecimal getMontoReserva() { return montoReserva; }
    public Instant getFechaCreacion() { return fechaCreacion; }
    public String getTipoBoleto() { return tipoBoleto; }
    public EstadoBoleto getEstado() { return estado; }
    public Instant getFechaExpiracionCupo() { return fechaExpiracionCupo; }
    public boolean isCupoRetenido() { return cupoRetenido; }
    public boolean isCobroEjecutado() { return cobroEjecutado; }
    public int getIntentosValidacion() { return intentosValidacion; }
    public Instant getProximoReintento() { return proximoReintento; }
    public String getMotivoRechazo() { return motivoRechazo; }

    @Override
    public String toString() {
        return "BoletoTuristico{" +
                "idReserva='" + idReserva + '\'' +
                ", numeroBoleto='" + numeroBoleto + '\'' +
                ", titular='" + titular + '\'' +
                ", estado=" + estado +
                ", cupoRetenido=" + cupoRetenido +
                ", cobroEjecutado=" + cobroEjecutado +
                ", intentosValidacion=" + intentosValidacion +
                '}';
    }
}
