# Entregable E7: Bitácora de Asistencia y Verificación de Inteligencia Artificial

**Curso:** Construcción de Software (1703243) · Semestre 2026-B  
**Institución:** Escuela Profesional de Ingeniería de Sistemas (EPIS) — Universidad Nacional de San Agustín de Arequipa (UNSA)  
**Actividad:** Caso Práctico Grupal AG-10 — Diseño de la arquitectura de RutaColca  
**Grupo:** Grupo 5  
**Integrantes:**
- Condori Caira Antony Beltran (Redactor)
- Quiñonez Delgado Aarón Fernando (Arquitecto de Software)
- Sencia Ale Bryan Daniel (Evaluador)
- Yauli Merma Diego Raul (Facilitador ADD)  
**Herramientas de IA Consultadas:** Asistente LLM (Codex / Claude 3.5 Sonnet / Antigravity)  
**Regla de Oro:** *"La IA propone, el equipo decide y verifica"*. Se prohíbe el copiado acrítico y se documenta cada descarte o ajuste técnico.

---

## 1. Registro Consolidado de Prompts P1 a P5

| Paso / Prompt | Propuesta Inicial de la IA | Decisión del Equipo (Aceptado / Rechazado / Corregido) | Justificación Técnica de la Decisión del Equipo | Integrante Verificador |
| :--- | :--- | :--- | :--- | :--- |
| **P1: Cuestionar la Priorización (Árbol de Utilidad)**<br>*Prompt P1 del caso* | La IA sugirió calificar el escenario **E4 (PayPal)** como **(A, A)** alegando la alta complejidad de integración con APIs de pagos internacionales, y sugirió calificar **E5 (AUTOCOLCA)** como **(M, M)** asumiendo que un retry simple en memoria bastaría. | **CORREGIDO** | El equipo **rechazó la propuesta de la IA**: E4 es **(M, B)** porque el monolito modular ya cuenta con el puerto `PasarelaPagos` desacoplado mediante el patrón *Strategy/Adapter*, lo que permite implementar el cliente HTTP de PayPal en menos de 3 días sin alterar el dominio. En contraste, E5 es ineludiblemente **(A, A)** debido a que AUTOCOLCA es un sistema externo sin SLA que colapsa los hilos de la API si no se desacopla temporalmente, con riesgo legal de fraude contable. | **Yauli Merma Diego Raul** *(Facilitador ADD)* |
| **P2: Iteración de ADD Asistida (Paso 3 ADD)**<br>*Prompt P2 del caso* | La IA propuso como Alternativa A migrar el módulo de reservas a una **arquitectura de Microservicios independiente desplegada en AWS EKS**, comunicada mediante **Apache Kafka (AWS MSK)** con tópicos particionados. | **RECHAZADO TAJANTEMENTE** | La propuesta viola directamente la restricción inquebrantable del caso: **mantener el Monolito Modular (ADR-001)** y la prohibición expresa de microservicios. Además, Kafka en AWS MSK genera un costo base superior a los $250 USD/mes (presupuesto asignado: $0 USD/mes) y es inalcanzable para un equipo de 4 personas en 3 semanas. El equipo instruyó a la IA a comparar una **cola transaccional en PostgreSQL (Transactional Outbox con ShedLock)** vs **RabbitMQ gestionado (CloudAMQP)**. | **Quiñonez Delgado Aarón Fernando** *(Arquitecto de Software)* |
| **P3: Revisión del ADR (Paso 4)**<br>*Prompt P3 del caso* | En la sección de consecuencias, la IA propuso: *"Para no perder ventas, el sistema debe cobrar la tarjeta inmediatamente y, si 2 horas después AUTOCOLCA rechaza el boleto, se emite una nota de crédito o reembolso diferido al turista"*. | **RECHAZADO TAJANTEMENTE** | El equipo **vetó con severidad esta propuesta**: contradice abiertamente el requisito y medida del Escenario E5: **"0 cobros a boletos inválidos"**. Realizar cargos monetarios previos a la validación provocaría penalidades bancarias por contracargos (*chargebacks*), comisiones de pasarela no recuperables y demandas de protección al consumidor (Indecopi). Se redactó la **condición de guarda obligatoria**: ningún cobro se ejecuta si el estado no es formalmente `VALIDADO`. | **Condori Caira Antony Beltran** *(Redactor)* |
| **P4: Evaluador ATAM Ligero (Paso 5)**<br>*Prompt P4 del caso* | La IA generó un reporte ATAM genérico identificando únicamente *"Riesgo de que el servidor se quede sin memoria"*, sin cuantificar puntos de sensibilidad ni analizar el impacto en los otros 4 escenarios. | **CORREGIDO Y PROFUNDIZADO** | El equipo reformuló la evaluación: identificó el riesgo real de **retención temporal de cupos fantasma** en temporada alta (afectando E1), formalizó los dos puntos de sensibilidad críticos (**Intervalo de backoff exponencial** para evitar *Thundering Herd* sobre AUTOCOLCA, y **TTL de 120 minutos** para balancear tolerancia de fallo vs rotación de inventario), y contrastó el trade-off de consistencia eventual frente a confirmación instantánea. | **Sencia Ale Bryan Daniel** *(Evaluador)* |
| **P5: Cambios en el Modelo C4 Structurizr (Paso 6)**<br>*Prompt P5 del caso* | La IA propuso añadir un contenedor externo nuevo llamado `WorkerService` en el diagrama de contenedores y redactó la vista dinámica omitiendo el paso de consulta a la base de datos y la notificación por WhatsApp. | **CORREGIDO** | Un nuevo contenedor violaría el monolito modular (ADR-001); el worker es un **componente interno** dentro de `api` (`ruta.api.workerAutocolca`) y una tabla outbox en `bd`. Además, el evaluador de Structurizr CLI arrojó error de sintaxis si se referenciaban componentes dentro de una vista dinámica con scope de software system; el equipo ajustó las relaciones a nivel de contenedor para cumplir con los 10 pasos exactos y lograr validación limpia en `structurizr-cli validate`. | **Quiñonez Delgado Aarón Fernando** *(Arquitecto)* y **Condori Caira Antony Beltran** *(Redactor)* |

---

## 2. Detalle de Correcciones Críticas y Aprendizajes del Grupo

### Corrección Crítica 1: El sesgo de la IA hacia Microservicios y Herramientas Cloud Costosas
- **Incidente:** En el prompt P2, los modelos LLM tienden recurrentemente a proponer *Apache Kafka*, *AWS SQS*, *AWS Lambda* o *Event-Driven Microservices* como respuesta automática a problemas de interoperabilidad asíncrona.
- **Acción del Grupo:** El facilitador y el arquitecto recordaron el contexto real: RutaColca es un consorcio regional en Arequipa con un equipo de 4 ingenieros, un plazo de 3 semanas y presupuesto cero ($0 USD/mes). Se forzó a la IA a diseñar sobre el patrón *Transactional Outbox* en PostgreSQL 16 utilizando `SKIP LOCKED` y `ShedLock`, demostrando que el monolito modular tiene capacidades nativas suficientes sin añadir costos de infraestructura.

### Corrección Crítica 2: La "solución perezosa" de cobrar antes de validar
- **Incidente:** En el prompt P3, la IA intentó simplificar el flujo proponiendo cobrar de inmediato al turista y luego reembolsar si el boleto era inválido.
- **Acción del Grupo:** El redactor y el evaluador detectaron que esta sugerencia violaba directamente el driver del negocio ("0 cobros a boletos inválidos"). Se corrigió la lógica creando una **máquina de estados explícita** (`PENDIENTE_VALIDACION` $\to$ `VALIDADO` $\to$ `COBRADO`) con condición de guarda inmutable en código Java (`assert estado == VALIDADO`).

### Corrección Crítica 3: Respeto estricto a las restricciones del modelo C4 en Structurizr DSL
- **Incidente:** La IA inventó sintaxis no soportada en Structurizr DSL (como intentar conectar componentes internos dentro de una vista dinámica de nivel de sistema).
- **Acción del Grupo:** El equipo descargó y ejecutó localmente la herramienta oficial `structurizr-cli validate`, corrigiendo la jerarquía de identificadores jerárquicos (`!identifiers hierarchical`) y garantizando que los diagramas generen exportaciones válidas tanto en PlantUML como en Mermaid.
