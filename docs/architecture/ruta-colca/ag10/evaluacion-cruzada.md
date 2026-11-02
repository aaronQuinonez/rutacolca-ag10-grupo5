# Entregable E4: Evaluación Cruzada de la Arquitectura (Estilo ATAM Ligero)

**Curso:** Construcción de Software (1703243) · Semestre 2026-B  
**Institución:** Escuela Profesional de Ingeniería de Sistemas (EPIS) — Universidad Nacional de San Agustín de Arequipa (UNSA)  
**Actividad:** Caso Práctico Grupal AG-10 — Evaluación de Arquitectura  
**Grupo Evaluador:** Grupo 5 (Evaluador: Sencia Ale Bryan Daniel / Facilitador: Yauli Merma Diego Raul)  
**Decisión Evaluada:** ADR-005 — Validación asíncrona de boletos turísticos con cola transaccional en PostgreSQL y cobro diferido

---

## 1. Metodología de Evaluación ATAM Ligero

Conforme al método *Architecture Tradeoff Analysis Method* (ATAM, Kazman et al., CMU/SEI-2000-TR-004), se evalúa el impacto de la decisión arquitectónica adoptada no solo frente al escenario motivador (E5), sino transversalmente frente a los **cinco escenarios de calidad** del sistema:
- **Riesgo:** Una decisión arquitectónica que puede comprometer el cumplimiento de un requisito de calidad o generar efectos secundarios no deseados en la operación del sistema.
- **Punto de Sensibilidad:** Un parámetro de diseño o configuración técnica del cual depende críticamente el valor numérico de una medida de respuesta.
- **Trade-off (Compromiso arquitectónico):** Una decisión de diseño que beneficia notablemente un atributo de calidad a expensas de degradar otro.

---

## 2. Matriz de Evaluación Cruzada Estilo ATAM

### Tabla 1. Análisis de Riesgos, Puntos de Sensibilidad y Trade-offs frente a los 5 Escenarios

| Tipo | Descripción Técnica del Hallazgo | Escenario Afectado | Impacto Arquitectónico y Estrategia de Mitigación |
| :--- | :--- | :--- | :--- |
| **Riesgo** | **Retención temporal de cupos fantasma por boletos inválidos:** Al aceptar reservas en estado `PENDIENTE_VALIDACION` durante una caída de AUTOCOLCA de hasta 2 horas, turistas o agencias podrían ingresar boletos inválidos, caducados o inventados. Mientras dure la caída, el cupo de la salida queda retenido, reduciendo la disponibilidad real de inventario para turistas con boletos legítimos en horas pico. | **E1 (Rendimiento / Disponibilidad de inventario)** y **E5 (Interoperabilidad)** | **Severidad: Media.** Se mitiga fijando un límite máximo de cupos en estado provisional por salida ($\le 20\%$ de la capacidad del vehículo) y un proceso automático de expiración que desaloja y devuelve el cupo al inventario general inmediatamente al recibir el rechazo de AUTOCOLCA o al expirar las 2 horas. |
| **Riesgo** | **Agotamiento de la ventana de 2 horas ante caída prolongada:** Si el corte de servicio de AUTOCOLCA supera los 120 minutos continuos, las reservas encoladas alcanzarán el tiempo límite de expiración, provocando la cancelación automática de la reserva temporal y frustración en turistas que tenían boletos válidos. | **E5 (Interoperabilidad)** y **E2 (Disponibilidad percibida)** | **Severidad: Baja-Media.** Se mitiga mediante un canal de contacto prioritario que notifica al turista invitándolo a revalidar con un clic prioritario antes de que el cupo sea comprado por terceros, o notificando al operador de agencia para revalidación manual. |
| **Punto de Sensibilidad 1** | **Intervalo de polling y curva de Backoff Exponencial con Jitter:** El tiempo base del worker ($\text{polling} = 30\text{ s}$) y el algoritmo de reintento ($30\text{s}, 60\text{s}, 120\text{s}, 300\text{s}\dots$ con variación aleatoria de $\pm 5\text{ s}$). Si el intervalo es demasiado agresivo ($< 5\text{ s}$), al restablecerse AUTOCOLCA ambas réplicas generarán un efecto de avalancha (*Thundering Herd*) que volverá a derribar el servicio externo. Si es demasiado lento ($> 15\text{ min}$), se violará la medida de validación en $\le 2\text{ horas}$. | **E5 (Interoperabilidad)** y **E1 (Rendimiento del sistema)** | **Sensibilidad: Crítica.** Se calibra con `initialInterval = 30s`, `multiplier = 2.0`, `maxInterval = 300s`, `jitter = 0.1` y procesamiento por lotes con `LIMIT 50`. |
| **Punto de Sensibilidad 2** | **Tiempo de Vida (TTL) de la retención de cupo:** El parámetro `TTL_CUPO_TEMPORAL` está fijado estrictamente en **120 minutos (2 horas)**. Si se reduce (p. ej. a 30 minutos), se incumple el escenario E5 que exige tolerar caídas de AUTOCOLCA de hasta 2 horas. Si se amplía (p. ej. a 6 horas), se agrava la retención de inventario no vendido en salidas que parten al día siguiente. | **E5 (Interoperabilidad)** y **E1 (Disponibilidad de inventario)** | **Sensibilidad: Alta.** El parámetro de 120 minutos equilibra de forma matemáticamente exacta la tolerancia máxima de caída con la rotación comercial de cupos en el Colca. |
| **Punto de Sensibilidad 3** | **Filtro de contención concurrente en base de datos (`SKIP LOCKED` y tamaño de lote):** Al existir 2 réplicas de la API en Docker consultando concurrentemente la tabla `cola_validacion_boletos`, el uso de `FOR UPDATE SKIP LOCKED` y un tamaño de lote `BATCH_SIZE = 50` evita el bloqueo de registros entre réplicas y garantiza que el índice parcial `idx_cola_boletos_pendientes` se mantenga caliente en la memoria caché de PostgreSQL. | **E1 (Rendimiento)** | **Sensibilidad: Media.** Previene colisiones de bloqueo entre hilos de ejecución concurrentes en ambas VMs. |
| **Trade-off** | **Corrección de negocio y resiliencia (Disponibilidad/Interoperabilidad) vs. Inmediatez de confirmación (Experiencia de usuario sincrónica):** Se prioriza la garantía de **0 cobros a boletos no válidos** y la continuidad del proceso de reserva frente a la inmediatez de la confirmación instantánea. El turista debe tolerar un estado provisional diferido a cambio de que su cupo quede resguardado sin cargos indebidos en su tarjeta. | **E5 (Interoperabilidad)** frente a **Experiencia de Reserva Inmediata** | **Decisión justificada:** El costo comercial y legal de cobrar a boletos inválidos supera por amplio margen la fricción momentánea de una validación asíncrona bien comunicada al turista. |
| **Trade-off** | **Austeridad de infraestructura ($0 USD/mes, Monolito) vs. Aislamiento físico de colas (Microservicios / Message Broker):** Se elige utilizar PostgreSQL como cola transaccional dentro del monolito en lugar de aprovisionar un clúster RabbitMQ/Kafka. Se gana simplicidad, entrega en 3 semanas y costo de $0 USD/mes, sacrificando la capacidad de desacoplar el procesamiento a millones de eventos por segundo (lo cual no es requerido para la escala de RutaColca). | **E4 (Modificabilidad/Simplicidad)** frente a **Escalabilidad Extrema de Mensajería** | **Decisión justificada:** Para una carga pico de 2000 reservas por día en el Colca, PostgreSQL maneja la cola con menos del 2% de uso de CPU adicional. |

---

## 3. Evaluación de la Decisión frente a los demás Escenarios del Sistema

1. **Impacto sobre E1 (Rendimiento: 2000 turistas consultan salidas $\le 1.5\text{ s}$):**
   - **Favorable:** Al eliminar las llamadas síncronas bloqueantes hacia AUTOCOLCA en el registro de reservas, los hilos de Tomcat no se saturan, manteniendo la capacidad de atención rápida para las 2000 consultas simultáneas de disponibilidad de salidas.
   - **Riesgo controlado:** El polling del worker se realiza sobre un índice parcial en PostgreSQL, consumiendo menos de 10 milisegundos por ejecución de lote.
2. **Impacto sobre E2 (Disponibilidad: Pasarela de pagos no responde durante 15 min):**
   - **Favorable y sinérgico:** El diseño desacoplado de cobro diferido encaja naturalmente con la resiliencia de la pasarela de pagos. Si la pasarela tampoco responde, la reserva validada simplemente se reprograma para cobro diferido mediante el mismo mecanismo de reintentos, sin perder la transacción.
3. **Impacto sobre E3 (Seguridad: Bloqueo de modificación indebida de tours por otra agencia - BOLA):**
   - **Neutral / Ortogonal:** La validación asíncrona de boletos no altera las políticas de autorización ni la validación del identificador de agencia en los controladores del dominio de tours.
4. **Impacto sobre E4 (Modificabilidad: Integrar PayPal en $\le 3\text{ días-persona}$ sin cambios en dominio):**
   - **Favorable:** Debido a que el cobro diferido se orquesta a través del puerto existente `PasarelaPagosPort`, cualquier pasarela compatible (como PayPal) se beneficiará automáticamente del cobro diferido sin modificar la lógica del worker ni el modelo de reservas.

---

## 4. Dictamen del Equipo Evaluador

El diseño propuesto en el **ADR-005** es calificado como **SÓLIDO, FACTIBLE Y DE BAJO RIESGO**. Los dos puntos de sensibilidad críticos (intervalo de backoff y TTL de 120 minutos) están formalmente acotados por parámetros configurables en `application.yml`, y el trade-off asumido protege plenamente la integridad financiera y reputacional del consorcio RutaColca.
