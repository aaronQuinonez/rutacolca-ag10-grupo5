# Entregable E2: Aplicación de ADD 3.0 y Comparación Rigurosa de Alternativas

**Curso:** Construcción de Software (1703243) · Semestre 2026-B  
**Institución:** Escuela Profesional de Ingeniería de Sistemas (EPIS) — Universidad Nacional de San Agustín de Arequipa (UNSA)  
**Caso Práctico:** AG-10 — Diseño de la arquitectura de RutaColca  
**Grupo:** Grupo 5 (Escenario E5 — Interoperabilidad)  
**Equipo de Arquitectura:**
- Condori Caira Antony Beltran (Redactor)
- Quiñonez Delgado Aarón Fernando (Arquitecto de Software)
- Sencia Ale Bryan Daniel (Evaluador)
- Yauli Merma Diego Raul (Facilitador ADD)

---

## 1. Aplicación del Método ADD 3.0 (Pasos 2 a 5)

El método *Attribute-Driven Design* (ADD 3.0, Cervantes & Kazman) estructura el diseño arquitectónico a través de iteraciones guiadas por drivers de calidad específicos. Para el Escenario E5, se ejecutan los pasos 2 a 5:

### Paso 2: Objetivo de la Iteración
Diseñar una solución arquitectónica que resuelva la degradación e indisponibilidad síncrona del sistema externo de AUTOCOLCA, garantizando:
1. **Validación diferida:** Procesar y validar el 100% de las reservas pendientes en un plazo $\le 2\text{ horas}$ tras el restablecimiento del servicio de AUTOCOLCA.
2. **Garantía contable absoluta:** Asegurar **0 cobros** monetarios a turistas cuyos boletos turísticos no hayan sido confirmados previamente como válidos por AUTOCOLCA.
3. **Respeto a las restricciones inquebrantables del proyecto:**
   - Preservar el monolito modular en Java 21 y Spring Boot (ADR-001).
   - Utilizar la infraestructura aprobada (PostgreSQL 16 gestionado, 2 réplicas Docker sobre VM Ubuntu 24.04).
   - Entrega operativa en un plazo máximo de **3 semanas** por un equipo de **4 desarrolladores**.
   - Justificar cualquier costo operativo mensual adicional en USD (priorizando costo $\$0\text{ USD/mes}$).

### Paso 3: Elementos a Refinar del Sistema
Del modelo arquitectónico C4 base (Figura 1 del caso / ADR-001), los elementos sometidos a refinamiento son:
- **Contenedor `API de RutaColca` (`ruta.api`):**
  - Desacoplar la llamada a AUTOCOLCA del ciclo de vida de la petición HTTP del usuario (evitar bloqueo de hilos de Tomcat y timeouts a clientes).
  - Separar la reserva física de cupos de la ejecución del cobro en la pasarela de pagos.
  - Implementar un mecanismo de ejecución asíncrona de reintentos con control de concurrencia para las 2 réplicas del contenedor.
- **Contenedor `Base de datos` (`ruta.bd` - PostgreSQL 16):**
  - Persistir el estado transicional de la reserva (`PENDIENTE_VALIDACION`), la marca temporal de expiración de cupo (TTL) y el registro atómico de tareas diferidas.

### Paso 4: Selección de Conceptos de Diseño (Tácticas de Arquitectura)
Conforme al catálogo de Bass et al. (4ta edición), se seleccionan las siguientes tácticas arquitectónicas:

1. **Tácticas de Interoperabilidad:**
   - *Gestionar interfaces — Orquestación diferida (Decoupling in time):* Se rompe la dependencia temporal síncrona cliente-servidor; la interacción se divide en una fase de recepción inmediata y una fase de resolución eventual.
   - *Gestionar interfaces — Adaptar la interfaz (Puerto y Adaptador):* Se aísla el protocolo y contrato de datos de AUTOCOLCA mediante la interfaz `ValidadorBoletoPort` y adaptadores intercambiables.
2. **Tácticas de Disponibilidad y Recuperación:**
   - *Detectar fallas — Tiempo de espera (Timeouts acotados):* Se establece un límite de 2.5 segundos para la llamada HTTP a AUTOCOLCA antes de asumir degradación y activar el flujo diferido.
   - *Recuperarse de fallas — Reintentos con Backoff Exponencial y Jitter:* Se reintenta la comunicación espaciando las llamadas progresivamente (ej. 30s, 60s, 120s, 300s...) con variación aleatoria para evitar el efecto de avalancha (*Thundering Herd*) sobre AUTOCOLCA al recuperarse.
   - *Prevenir fallas — Transacciones ACID (Transactional Outbox):* La reserva en estado provisional y la tarea de validación se insertan en la misma transacción relacional, impidiendo estados inconsistentes (*dual-write problem*).
3. **Tácticas de Rendimiento y Control de Recursos:**
   - *Gestionar recursos — Bloqueo temporal con tiempo de vida (TTL):* Se retiene el cupo en la salida por un máximo de 120 minutos; si la validación falla o expira, el inventario se libera de forma automática.
   - *Controlar la demanda — Procesamiento por lotes y Skip Locked:* Los workers procesan bloques de tareas pendientes utilizando `SELECT ... FOR UPDATE SKIP LOCKED` para no saturar las conexiones ni bloquear transacciones operativas.

### Paso 5: Instanciar Elementos y Comparar Alternativas
Se formulan dos alternativas técnicas viables que aplican estas tácticas, evaluando sus trade-offs de ingeniería.

---

## 2. Descripción de las Alternativas de Solución

```
+---------------------------------------------------------------------------------------------------+
| ALTERNATIVA A: Cola Transaccional en PostgreSQL (Outbox + Job Scheduler) [$0 USD/mes] (ELEGIDA)   |
| API RutaColca (Spring Boot @Scheduled + ShedLock) <---> PostgreSQL 16 (tabla outbox + SKIP LOCKED)|
+---------------------------------------------------------------------------------------------------+
| ALTERNATIVA B: Broker Externo RabbitMQ Gestionado en Cloud (CloudAMQP) [~$35 USD/mes] (DESCARTADA)|
| API RutaColca (Spring AMQP) <---> CloudAMQP Clúster (Colas con DLQ / Per-message TTL)             |
+---------------------------------------------------------------------------------------------------+
```

### Alternativa A (Elegida): Cola Transaccional en PostgreSQL (Transactional Outbox con Spring Boot @Scheduled y ShedLock)

#### Arquitectura y Funcionamiento
1. **Recepción del estímulo:** Cuando el turista envía su reserva con el número de boleto turístico, la API intenta una validación rápida con un timeout de 2.5 segundos. Si la llamada falla o excede el tiempo límite, se activa el flujo resiliente.
2. **Consistencia atómica local:** Dentro de una única transacción ACID en PostgreSQL:
   - Se crea la reserva con estado `PENDIENTE_VALIDACION`.
   - Se bloquea provisionalmente el cupo de la salida asignando `cupo_expira_en = NOW() + INTERVAL '120 minutes'`.
   - Se inserta un registro en la tabla `cola_validacion_boletos` con `estado = 'PENDIENTE'`, `proximo_reintento = NOW() + INTERVAL '30 seconds'`, `intentos = 0` y `payload_boleto`.
3. **Respuesta inmediata al usuario:** Se retorna un código HTTP 202 Accepted. En el frontend se muestra el estado diferido sin solicitar aún los fondos de la tarjeta ni invocar la pasarela de pagos.
4. **Worker programado y coordinación distribuida:**
   - Un componente `ValidadorBoletoWorker` dentro del monolito se ejecuta periódicamente (cada 30 segundos) anotado con `@Scheduled`.
   - Para evitar que las 2 réplicas de la API en Docker procesen la misma tarea simultáneamente, se utiliza **ShedLock** respaldado en una tabla auxiliar de PostgreSQL (`shedlock`), o consultas con `SELECT id FROM cola_validacion_boletos WHERE estado = 'PENDIENTE' AND proximo_reintento <= NOW() LIMIT 50 FOR UPDATE SKIP LOCKED`.
5. **Reintentos y resolución:**
   - El worker invoca al `Adaptador AUTOCOLCA`. Si este sigue caído, incrementa el contador de intentos y reprograma `proximo_reintento` aplicando retroceso exponencial con jitter ($t_{next} = \min(2^{intentos} \times 30\text{s} \pm \text{jitter}, 900\text{s})$).
   - **Caso Boleto Válido:** AUTOCOLCA valida el boleto. El worker actualiza la reserva a `VALIDADO`, consolida el cupo como definitivo, y recién en este instante invoca al puerto `PasarelaPagos` para cobrar la reserva (o notifica al turista para cobro por enlace seguro). Una vez cobrado con éxito, se despacha la confirmación y el itinerario por WhatsApp.
   - **Caso Boleto Inválido o Fraude:** AUTOCOLCA responde que el boleto no existe o ya fue usado. El worker actualiza la reserva a `RECHAZADO`, libera de inmediato el cupo provisional de la salida y notifica al turista. **Nunca se interactúa con la pasarela de pagos (0 cobros garantizados)**.
   - **Caso Expiración (TTL > 2h sin recuperación):** Si se alcanzan las 2 horas sin respuesta, la reserva transiciona a `EXPIRADO`, el cupo se libera y se notifica al turista para que reintente más tarde.

#### Costos y Plazos
- **Costo de infraestructura adicional:** **$0 USD/mes** (utiliza el servidor de base de datos PostgreSQL 16 gestionado ya provisionado).
- **Costo de desarrollo:** 4 a 6 días-persona (configurar tabla de outbox, servicio worker, reintentos con backoff y pruebas unitarias/integración).

---

### Alternativa B (Descartada): Broker Externo RabbitMQ Gestionado en Cloud (CloudAMQP) con Dead Letter Queue

#### Arquitectura y Funcionamiento
1. **Recepción y publicación asíncrona:** Al fallar la llamada síncrona a AUTOCOLCA, la API guarda la reserva en estado pendiente y publica un mensaje AMQP a un *Exchange* en un clúster gestionado de RabbitMQ (ej. servicio CloudAMQP).
2. **Topología de colas de reintento:**
   - Cola principal: `validacion.autocolca.cola`.
   - Cola de espera / reintento con retardo: `validacion.autocolca.retry.cola` configurada con *per-message TTL* y *dead-letter-exchange* (DLX) apuntando de vuelta a la cola principal.
   - Cola de errores terminales: `validacion.autocolca.dlq`.
3. **Consumidores distribuidos:** Ambas réplicas de la API corren consumidores con Spring AMQP (`@RabbitListener`). Cada mensaje recibido desencadena la llamada al adaptador de AUTOCOLCA. Si la llamada falla, el consumidor rechaza el mensaje con `basic.reject(requeue = false)`, enviándolo a la cola de reintento por DLX.
4. **Motivos de descarte:**
   - **Costo operativo injustificado:** Requiere suscribirse a una instancia dedicada gestionada en la nube (ej. plan "Lemur" o "Tiger" de CloudAMQP) con un coste base aproximado de **$35 a $50 USD/mes**, violando el principio de austeridad y justificación de costos del proyecto.
   - **Problema de la Doble Escritura (Dual-Write Problem):** Guardar en PostgreSQL y publicar en RabbitMQ no es atómico. Si la base de datos confirma pero la red hacia el broker falla, el mensaje se pierde (a menos que se implemente de todos modos una tabla de Outbox en PostgreSQL, lo cual anula la ventaja de RabbitMQ e introduce redundancia innecesaria).
   - **Sobrecarga de operación y monitoreo:** El equipo consta de únicamente 4 desarrolladores. Administrar túneles VPC/seguridad de red, monitorear el broker, gestionar credenciales y mantener colas DLQ sobrepasa la capacidad operativa requerida en un plazo de 3 semanas.

---

## 3. Matriz Comparativa Rigurosa de Alternativas

### Tabla 3. Matriz comparativa entre la Alternativa A y la Alternativa B

| Criterio de Decisión | Alternativa A: Cola Transaccional en PostgreSQL (Elegida) | Alternativa B: Broker Externo RabbitMQ Cloud (Descartada) |
| :--- | :--- | :--- |
| **Tácticas arquitectónicas (Bass et al.)** | **Interoperabilidad:** Orquestación diferida, Adaptador de interfaz.<br>**Disponibilidad:** Timeouts, Reintentos con Backoff Exponencial y Jitter, Transacciones ACID (Outbox).<br>**Rendimiento:** Gestión de recursos (TTL de cupos, `SKIP LOCKED`). | **Interoperabilidad:** Orquestación asíncrona por paso de mensajes.<br>**Disponibilidad:** Reintento por DLX (Dead Letter Exchange), Timeouts.<br>**Rendimiento:** Concurrencia desacoplada de hilos. |
| **Patrón y Tecnologías clave** | Patrón *Transactional Outbox*, Job Scheduler con Spring Boot `@Scheduled`, `ShedLock` / `PostgreSQL SKIP LOCKED`. | Patrón *Message Broker*, Topología AMQP con Dead Letter Queues, Spring AMQP (`@RabbitListener`), CloudAMQP. |
| **Cumplimiento de la medida 1 (Validación $\le 2\text{ h}$)** | **Excelente:** El worker con polling a 30s y backoff exponencial detecta el restablecimiento de AUTOCOLCA en menos de 1 minuto y drena las reservas acumuladas en segundos. | **Excelente:** Consumo instantáneo de eventos en cuanto se restablece la comunicación. |
| **Cumplimiento de la medida 2 (0 cobros a boletos inválidos)** | **Total y garantizado por diseño:** El puerto de pago tiene una condición de guarda estricta que exige que la reserva esté en estado `VALIDADO`. Si el boleto es inválido, nunca se invoca la pasarela. | **Garantizado:** El listener condicional valida antes de publicar al flujo de pago. |
| **Costo de desarrollo** | **Bajo (4 a 6 días-persona):** Aprovecha el stack conocido de Java 21, Spring Data JPA y PostgreSQL. No requiere nuevas librerías complejas. | **Medio-Alto (10 a 14 días-persona):** Requiere diseñar topologías AMQP, gestión de Dead Letter Exchanges, configuración de red segura y manejo del dual-write. |
| **Costo operativo mensual (USD/mes)** | **$0 USD/mes adicionales:** Utiliza la base de datos gestionada PostgreSQL 16 existente, sin aprovisionar infraestructura cloud externa. | **~$35 a $50 USD/mes adicionales:** Requiere aprovisionar y pagar un servicio gestionado de RabbitMQ en la nube (ej. CloudAMQP). |
| **Riesgos técnicos** | **Carga de polling en PostgreSQL:** Mitigado eficazmente con índices parciales (`WHERE estado = 'PENDIENTE'`) y lecturas por lotes con `SKIP LOCKED`.<br>**Bloqueo de cupos:** Mitigado con TTL de 2 horas. | **Dual-Write Problem:** Inconsistencia entre el commit de la reserva y el envío al broker.<br>**Sobrecarga de mantenimiento:** Monitorear clúster AMQP adicional. |
| **¿Respeta las restricciones inquebrantables?** | **SÍ:**<br>- Preserva el monolito modular (ADR-001).<br>- Factible en menos de 3 semanas (4 devs).<br>- Mantiene Java 21, Spring Boot y PostgreSQL 16.<br>- Costo adicional de **$0 USD/mes**. | **NO / CUESTIONABLE:**<br>- Agrega infraestructura externa no justificada ($35/mes).<br>- Riesgo de sobrepasar el plazo de 3 semanas debido a la curva de infraestructura.<br>- Sobrecarga operativa para un equipo pequeño de 4 devs. |

---

## 4. Conclusión del Proceso ADD

La **Alternativa A** es seleccionada formalmente como la solución arquitectónica óptima. Cumple estrictamente con ambas dimensiones de la medida de respuesta del Escenario E5 (validación $\le 2\text{ h}$ y 0 cobros a boletos inválidos), aprovecha al 100% las capacidades nativas de PostgreSQL 16 (transaccionalidad ACID y soporte `SKIP LOCKED`), preserva el monolito modular sin romper la frontera arquitectónica, no introduce costes operativos de nube ($0 USD/mes) y es ejecutable holgadamente por el equipo de 4 ingenieros en un plazo de 2 semanas de desarrollo.
