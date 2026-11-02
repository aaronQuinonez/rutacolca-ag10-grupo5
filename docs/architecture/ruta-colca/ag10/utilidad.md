# Entregable E1: Árbol de Utilidad y Especificación del Escenario E5

**Curso:** Construcción de Software (1703243) · Semestre 2026-B  
**Institución:** Escuela Profesional de Ingeniería de Sistemas (EPIS) — Universidad Nacional de San Agustín de Arequipa (UNSA)  
**Actividad:** Caso Práctico Grupal AG-10 — Diseño de la arquitectura de RutaColca  
**Grupo:** Grupo 5  
**Integrantes:**
- Condori Caira Antony Beltran
- Quiñonez Delgado Aarón Fernando
- Sencia Ale Bryan Daniel
- Yauli Merma Diego Raul  
**Escenario Asignado:** E5 (Interoperabilidad con AUTOCOLCA)

---

## 1. Paso 1: Árbol de Utilidad de RutaColca

El árbol de utilidad prioriza los atributos de calidad evaluando cada escenario bajo dos dimensiones ortogonales:
- **Importancia para el negocio (Imp.):** Impacto financiero, operativo y reputacional para el consorcio de agencias de turismo del valle del Colca. Escala: **A** (Alta), **M** (Media), **B** (Baja).
- **Dificultad técnica (Dif.):** Complejidad de implementación, nivel de incertidumbre y riesgo arquitectónico sobre la arquitectura base existente (monolito modular Spring Boot 3 / Java 21, 2 réplicas Docker, PostgreSQL 16 gestionado). Escala: **A** (Alta), **M** (Media), **B** (Baja).

### Tabla 1. Árbol de utilidad de los escenarios de calidad

| ID | Atributo de Calidad | Situación (Estímulo y Contexto) | Medida Esperada | Imp. | Dif. | Justificación Técnica |
| :--- | :--- | :--- | :--- | :---: | :---: | :--- |
| **E1** | **Rendimiento** | 2000 turistas consultan la disponibilidad de salidas en temporada alta simultáneamente. | $p95 \le 1.5\text{ s}$ | **A** | **M** | Consultas idempotentes de lectura que se resuelven optimizando índices en PostgreSQL y aplicando caché en memoria con invalidación selectiva sin mutar estado. |
| **E2** | **Disponibilidad** | La pasarela de pagos no responde durante 15 minutos en horario pico de ventas. | 0 reservas pagadas perdidas; confirmación $\le 30\text{ min}$ tras recuperación. | **A** | **A** | Exige orquestación asíncrona, conciliación de estados mediante webhooks idempotentes y colas de eventos resilientes para evitar pérdidas financieras directas. |
| **E3** | **Seguridad** | Un operador de agencia intenta modificar o cancelar un tour perteneciente a otra agencia. | 100% de intentos no autorizados bloqueados y auditados (BOLA / OWASP API1:2023). | **A** | **M** | Se implementa a nivel de aplicación mediante interceptores de autorización contextual (ABAC/RBAC) sobre el ID de agencia del token JWT y registro en tabla de auditoría. |
| **E4** | **Modificabilidad** | El consorcio solicita integrar PayPal como nuevo método de pago internacional. | Plazo $\le 3\text{ días-persona}$; 0 cambios en el modelo de dominio. | **M** | **B** | El monolito ya desacopla la pasarela mediante puertos y adaptadores (patrón Strategy/Adapter), requiriendo únicamente programar el nuevo adaptador HTTP cliente. |
| **E5** | **Interoperabilidad** | El sistema externo de AUTOCOLCA no responde al validar boletos turísticos durante horas de alta demanda. | 100% reservas pendientes validadas $\le 2\text{ h}$ tras restaurarse el servicio; 0 cobros a boletos inválidos. | **A** | **A** | Requiere desacoplar el flujo transaccional síncrono mediante Transactional Outbox, reintentos con backoff exponencial, reserva temporal de cupos con TTL y guarda estricta ante cobros indebidos. |

---

### Análisis de Escenarios Críticos (A, A)

Los escenarios calificados como **(A, A)** concentran el mayor riesgo técnico y de negocio de RutaColca:

1. **Escenario E2 (Disponibilidad - Pasarela de pagos):** Es de importancia crítica (**A**) porque la pérdida de una transacción pagada causa desconfianza inmediata en el turista y reclamos bancarios. Su dificultad técnica es alta (**A**) por la necesidad de garantizar idempotencia estricta, evitar dobles cobros y reconciliar transacciones en vuelo en un entorno distribuido sin conexión directa.
2. **Escenario E5 (Interoperabilidad - AUTOCOLCA) [Escenario del Grupo 5]:** Es de importancia crítica (**A**) para el consorcio debido a que operar con boletos turísticos no validados o cobrar tours a boletos adulterados acarrea sanciones legales por parte de la Autoridad Autónoma del Colca (AUTOCOLCA), descalificación del operador y pérdidas financieras por cobros no reembolsables. Su dificultad técnica es alta (**A**) porque AUTOCOLCA es una caja negra externa sin contrato de nivel de servicio (SLA) formal; una falla o latencia alta en su API propaga bloqueos en cascada al pool de hilos de la API de RutaColca, provocando denegación de servicio interna si la integración es síncrona. Desacoplar este proceso sin recurrir a una costosa arquitectura de microservicios y garantizando **cero cobros indebidos** en un plazo de desarrollo de 3 semanas demanda una solución de ingeniería rigurosa.

---

## 2. Paso 2: Especificación Formal del Escenario Asignado (E5)

Siguiendo la metodología de especificación de escenarios de calidad propuesta por Bass, Clements y Kazman (4ta edición), el escenario E5 se formula con sus seis partes constitutivas:

```
+-------------------------------------------------------------------------------------------------+
|                                    ESTRUCTURA DEL ESCENARIO E5                                  |
+-------------------------------------------------------------------------------------------------+
| Fuente:       Turista en Web SPA o agencia en Portal PWA.                                       |
| Estímulo:     Solicitud de reserva con número de boleto turístico ante AUTOCOLCA no disponible.  |
| Artefacto:    Contenedor API de RutaColca (Módulo de Reservas y Adaptador AUTOCOLCA).          |
| Entorno:      Temporada alta (julio/agosto), alta concurrencia, API externa en timeout/HTTP 5xx.|
| Respuesta:    Aceptación en PENDIENTE_VALIDACION, retención de cupo con TTL 2h, posponer cobro, |
|               encolado transaccional y notificación de procesamiento diferido.                 |
| Medida:       100% pendientes validadas <= 2h tras recuperación; 0 cobros a boletos inválidos. |
+-------------------------------------------------------------------------------------------------+
```

### Tabla 2. Especificación en seis partes del Escenario E5 (Interoperabilidad)

| Parte | Definición Teórica (Bass et al.) | Instanciación Formal para RutaColca (Escenario E5) |
| :--- | :--- | :--- |
| **Fuente del estímulo** | Entidad interna o externa que genera la solicitud o condición de entrada al sistema. | **Turista** que realiza una reserva a través de la aplicación web (*Web del turista*, React SPA) u **operador de agencia** que registra una venta presencial mediante el *Portal de agencias* (React PWA). |
| **Estímulo** | Evento, condición o carga que arriba al artefacto arquitectónico. | Envío de una solicitud de reserva que incluye uno o más números de **Boleto Turístico del Colca (BTC)**, en un instante donde el servicio web de validación de AUTOCOLCA se encuentra inaccesible (timeouts de conexión/lectura $> 2500\text{ ms}$, errores HTTP 502 Bad Gateway, 503 Service Unavailable o 504 Gateway Timeout). |
| **Artefacto** | Elemento de software o componente de la arquitectura estimulado. | **Contenedor "API de RutaColca"** (específicamente el componente interno `Módulo de Reservas`, el puerto `ValidadorBoleto` y el `Adaptador AUTOCOLCA`). |
| **Entorno** | Condiciones operativas, de infraestructura y de carga bajo las cuales ocurre el estímulo. | **Operación en temporada alta (julio - agosto y feriados de Arequipa)** con tráfico pico de reservas concurrentes, 2 réplicas de la API activas en contenedores Docker sobre VM Ubuntu 24.04, y el sistema de AUTOCOLCA caído o intermitente durante una ventana de hasta 120 minutos en horario estelar de ventas. |
| **Respuesta** | Comportamiento observable ejecutado por el artefacto ante la recepción del estímulo. | 1. La API detecta la indisponibilidad de AUTOCOLCA mediante un timeout controlado y evita el fallo en cascada.<br>2. Transiciona y persiste la reserva en estado `PENDIENTE_VALIDACION`.<br>3. **Retiene temporalmente el cupo** de la salida asociada con una marca de tiempo de expiración (TTL de 2 horas) para proteger el asiento del turista.<br>4. **Pospone la ejecución del cobro** monetario en la pasarela de pagos (condición de guarda: ningún cargo se ejecuta en estado pendiente).<br>5. Encola la solicitud en la tabla transaccional de outbox de validaciones pendientes en PostgreSQL dentro de la misma transacción ACID de la reserva.<br>6. Devuelve respuesta HTTP 202 Accepted al frontend informando al turista que su reserva ha sido registrada y se encuentra en proceso de validación diferida sin haberle realizado ningún cargo aún. |
| **Medida de respuesta** | Métrica cuantitativa y verificable que determina el éxito del requisito de calidad. | **1. Ventana de resolución:** El **100%** de las reservas en estado `PENDIENTE_VALIDACION` son validadas en un tiempo $\le 2\text{ horas}$ a partir del momento exacto en que la API de AUTOCOLCA restablece su servicio.<br>**2. Corrección contable/financiera:** Se ejecutan **exactamente 0 cobros** monetarios a turistas o agencias que hayan proporcionado boletos turísticos que resulten inválidos, inexistentes o duplicados.<br>**3. Integridad de cupos:** Los cupos retenidos de boletos inválidos o de solicitudes no validadas tras 2 horas se liberan automáticamente al inventario general sin intervención manual. |
