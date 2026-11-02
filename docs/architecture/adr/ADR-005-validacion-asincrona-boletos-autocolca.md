# ADR-005: Validación asíncrona de boletos turísticos con cola transaccional en PostgreSQL y cobro diferido

- **Estado:** Aceptado
- **Fecha:** 31/10/2026
- **Escenario:** E5 (Interoperabilidad con AUTOCOLCA)
- **Autores:** Grupo 5 — Condori Caira Antony Beltran, Quiñonez Delgado Aarón Fernando, Sencia Ale Bryan Daniel, Yauli Merma Diego Raul
- **Proyecto:** Plataforma RutaColca (Construcción de Software 2026-B, EPIS-UNSA)

---

## Contexto

En la arquitectura actual de RutaColca (ADR-001), cuando un turista o una agencia reserva un tour (HU-02 y HU-03), la API de RutaColca ejecuta una llamada HTTP estrictamente síncrona contra la API externa de AUTOCOLCA para validar la autenticidad y vigencia del Boleto Turístico del Colca (BTC).

Durante la temporada alta (julio, agosto y festividades de Arequipa), la API de AUTOCOLCA sufre intermitencias severas, latencias elevadas (> 10 segundos) o caídas prolongadas de hasta 2 horas. Esta integración síncrona causa los siguientes problemas críticos en producción:

1. **Fallo en cascada y agotamiento de recursos:** Las llamadas síncronas bloquean los hilos ejecutores del servidor de aplicaciones Tomcat en Spring Boot y saturan el pool de conexiones JDBC (HikariCP) de las 2 réplicas de la API, provocando errores HTTP 504 Gateway Timeout y denegación de servicio a turistas que simplemente navegan tours.
2. **Riesgo comercial y fraude contable:** Si el sistema cobrara el dinero en la pasarela antes de validar el boleto (o asumiera validación optimista), se cobrarían reservas con boletos falsos, duplicados o vencidos. Esto violaría los convenios con el consorcio y AUTOCOLCA, acarreando penalidades, costos financieros por comisiones bancarias no reembolsables y disputas por contracargos (*chargebacks*).
3. **Pérdida masiva de ventas:** Si el sistema simplemente rechaza la reserva ante la indisponibilidad de AUTOCOLCA, se pierden cientos de reservas legítimas durante las horas pico de tráfico.

### Restricciones del proyecto
- Preservar el monolito modular en Java 21 con Spring Boot y PostgreSQL 16 (ADR-001); no se permite migrar a microservicios.
- Plazo límite de desarrollo: **3 semanas** para un equipo de **4 desarrolladores**.
- Todo nuevo componente de infraestructura debe justificar su coste mensual de operación en dólares (USD); el presupuesto asignado para nueva infraestructura es de **$0 USD/mes**.

---

## Alternativas consideradas

- **Alternativa A (Tácticas de Gestión de Interfaces y Recuperación con Patrón Transactional Outbox en PostgreSQL):** Persistir la reserva en estado `PENDIENTE_VALIDACION`, retener el cupo temporalmente con un TTL de 120 minutos y registrar atómicamente la solicitud en una tabla de cola (`cola_validacion_boletos`) en PostgreSQL 16. Un worker programado en Spring Boot con `@Scheduled` y exclusión mutua mediante `ShedLock` (o `SKIP LOCKED`) reintenta las validaciones con backoff exponencial. El cobro en la pasarela solo se dispara si AUTOCOLCA retorna respuesta exitosa (`VALIDADO`). Costo de infraestructura adicional: **$0 USD/mes**.
- **Alternativa B (Tácticas de Reintento Distribuido con Patrón Message Broker externo - RabbitMQ gestionado):** Enviar las solicitudes de validación a un clúster RabbitMQ gestionado en la nube (CloudAMQP) con Dead Letter Exchanges (DLX) y políticas de expiración (per-message TTL) para orquestar los reintentos. Descartada porque introduce un costo recurrente de **~$35 USD/mes**, sobrecarga operativa de administración y el riesgo de inconsistencia transaccional (*dual-write problem*) entre la base de datos y el broker AMQP.

---

## Decisión

Se adopta la **Alternativa A**: implementar un flujo de **validación asíncrona de boletos turísticos utilizando el patrón Transactional Outbox respaldado en la base de datos PostgreSQL 16 existente, coordinado por un Job Scheduler en Spring Boot con ShedLock y cobro diferido condicionado**.

### Fundamentación técnica de la decisión:
1. **Garantía absoluta de 0 cobros indebidos:** Se establece una **condición de guarda estricta** en el módulo de reservas: bajo ninguna circunstancia se invoca al puerto `PasarelaPagos` mientras la reserva se encuentre en estado `PENDIENTE_VALIDACION`. El cobro se posterga hasta que el worker reciba la confirmación explícita de `VALIDADO` por parte de AUTOCOLCA.
2. **Cumplimiento de la ventana de validación ($\le 2\text{ horas}$):** El worker evalúa la cola cada 30 segundos aplicando retroceso exponencial con jitter ($30\text{s}, 60\text{s}, 120\text{s}, 300\text{s}\dots$). En el momento en que AUTOCOLCA restablece su servicio, el worker drena las tareas pendientes en un tiempo de respuesta de pocos minutos, cumpliendo holgadamente el límite de 2 horas.
3. **Consistencia atómica sin riesgo de pérdida:** La inserción de la reserva pendiente, el bloqueo temporal de cupos y el encolado en `cola_validacion_boletos` ocurren en una única transacción relacional local (`@Transactional`). No existe riesgo de perder tareas ni de inconsistencias distribuidas.
4. **Cero sobrecosto de infraestructura ($0 USD/mes):** Aprovecha la base de datos PostgreSQL 16 ya aprovisionada y administrada, evitando suscribir servicios adicionales.
5. **Alineación con el equipo y plazo:** Se implementa en 4 a 6 días-persona usando librerías estándar del ecosistema Spring (Spring Data JPA, Spring Scheduling, ShedLock), encajando cómodamente en el plazo de 3 semanas del equipo de 4 ingenieros.

---

## Consecuencias

### Positivas (+)
- **Cero cobros a boletos no válidos:** Ninguna tarjeta o billetera digital es debitada hasta que el boleto turístico cuenta con certificación formal de AUTOCOLCA.
- **Tolerancia a caídas de hasta 2 horas:** La plataforma absorbe indisponibilidades del sistema externo sin perder transacciones ni denegar servicio al turista.
- **Resiliencia de la API:** Se eliminan los bloqueos síncronos de hilos en Tomcat; la latencia percibida por el usuario en la reserva inicial desciende a menos de 400 ms (respuesta HTTP 202 Accepted).
- **Costo operativo de $0 USD/mes:** No se contratan brokers de mensajería ni servidores dedicados.
- **Preservación del Monolito Modular (ADR-001):** Todo el procesamiento se mantiene encapsulado dentro de los módulos `Reservas` e `Integraciones` del monolito existente.

### Negativas (−) y Mitigaciones
- **(−) Consistencia eventual y experiencia de usuario diferida:** El turista no recibe la confirmación instantánea en pantalla al momento de presionar el botón de reserva.  
  *Mitigación:* La interfaz (React SPA) informa con total transparencia que la reserva fue recibida en estado *"Pendiente de validación con AUTOCOLCA"*, entregando un código de seguimiento y comprometiendo la confirmación y cobro por correo/WhatsApp en un plazo máximo de 2 horas sin recargo.
- **(−) Retención de cupos fantasma por boletos fraudulentos:** Durante la caída de AUTOCOLCA, una persona podría ingresar un número de boleto falso, reteniendo temporalmente un cupo que podría ser comprado por otro turista.  
  *Mitigación:* Se implementa un **TTL estricto de expiración de cupo temporal fijado en 120 minutos (2 horas)**. Si al cabo de 2 horas AUTOCOLCA no valida el boleto o lo rechaza, la reserva pasa a estado `EXPIRADO` o `RECHAZADO` y el cupo se devuelve inmediatamente al inventario de la salida.
- **(−) Carga de consultas periódicas (polling) sobre PostgreSQL:** El worker consulta la tabla `cola_validacion_boletos` periódicamente, lo que podría generar sobrecarga en la base de datos en horas de alta concurrencia.  
  *Mitigación:* Se crea un **índice parcial especializado** en PostgreSQL:  
  `CREATE INDEX idx_cola_boletos_pendientes ON cola_validacion_boletos (proximo_reintento) WHERE estado = 'PENDIENTE';`  
  Esto hace que la consulta del worker lea únicamente las filas activas en milisegundos, con costo despreciable sobre el CPU del motor de base de datos. Además, se utiliza `FOR UPDATE SKIP LOCKED` para eliminar cualquier contención entre las 2 réplicas de la API.

---

## Impacto en el Modelo C4

- **Contenedores:** Se mantiene la topología de contenedores intacta. Dentro del contenedor `API de RutaColca`, se añade el componente `workerAutocolca` y el repositorio de la cola transaccional. En el contenedor `Base de datos`, se modela la tabla `cola_validacion_boletos`.
- **Relaciones:** La relación entre `API de RutaColca` y `AUTOCOLCA` pasa de ser puramente síncrona a un patrón diferido/reintento asíncrono gestionado por el worker.
- **Vistas:** Se incorpora la vista dinámica `ValidacionBoletoDiferida` en el `workspace.dsl`.
