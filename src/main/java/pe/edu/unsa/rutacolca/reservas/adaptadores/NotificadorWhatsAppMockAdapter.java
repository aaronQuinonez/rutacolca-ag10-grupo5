package pe.edu.unsa.rutacolca.reservas.adaptadores;

import pe.edu.unsa.rutacolca.reservas.puertos.NotificadorWhatsApp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adaptador simulado en memoria para WhatsApp Business.
 */
public class NotificadorWhatsAppMockAdapter implements NotificadorWhatsApp {

    private final AtomicInteger contadorMensajes = new AtomicInteger(0);
    private final List<String> mensajesEnviados = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void enviarMensaje(String destinatario, String mensaje) {
        contadorMensajes.incrementAndGet();
        mensajesEnviados.add("Para: " + destinatario + " | Mensaje: " + mensaje);
    }

    @Override
    public int getCantidadMensajesEnviados() {
        return contadorMensajes.get();
    }

    public List<String> getMensajesEnviados() {
        return Collections.unmodifiableList(mensajesEnviados);
    }
}
