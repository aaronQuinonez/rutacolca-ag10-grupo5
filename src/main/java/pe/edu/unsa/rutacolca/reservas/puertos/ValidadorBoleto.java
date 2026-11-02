package pe.edu.unsa.rutacolca.reservas.puertos;

import pe.edu.unsa.rutacolca.reservas.dominio.ResultadoValidacion;
import pe.edu.unsa.rutacolca.reservas.excepciones.AutocolcaUnavailableException;

/**
 * Puerto de salida para la validación de boletos turísticos contra AUTOCOLCA.
 * Sigue la arquitectura de puertos y adaptadores (Arquitectura Hexagonal).
 */
public interface ValidadorBoleto {

    /**
     * Valida el número de boleto turístico contra el sistema externo de AUTOCOLCA.
     *
     * @param numeroBoleto Código alfanumérico del boleto turístico del Colca (BTC).
     * @return ResultadoValidacion con el estado y datos del boleto.
     * @throws AutocolcaUnavailableException Si la llamada sufre timeout o error de servicio.
     */
    ResultadoValidacion validar(String numeroBoleto) throws AutocolcaUnavailableException;
}
