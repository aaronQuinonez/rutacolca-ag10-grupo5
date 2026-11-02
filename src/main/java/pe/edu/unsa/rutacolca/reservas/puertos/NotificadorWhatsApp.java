package pe.edu.unsa.rutacolca.reservas.puertos;

/**
 * Puerto de salida para el envío de itinerarios y confirmaciones vía WhatsApp Business.
 */
public interface NotificadorWhatsApp {

    /**
     * Envía un mensaje de notificación al turista o guía de turismo.
     */
    void enviarMensaje(String destinatario, String mensaje);

    /**
     * Retorna la cantidad de mensajes efectivamente despachados.
     */
    int getCantidadMensajesEnviados();
}
