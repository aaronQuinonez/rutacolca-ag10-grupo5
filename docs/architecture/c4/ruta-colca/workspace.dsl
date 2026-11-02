workspace "RutaColca" "Modelo C4 de RutaColca - Aarón Fernando Quiñonez Delgado (Construcción de Software 2026-B)" {

    !identifiers hierarchical
    !impliedRelationships true

    configuration {
        scope softwaresystem
    }

    properties {
        "c4plantuml.tags" "true"
        "c4plantuml.legend" "true"
    }

    model {
        // ---------- PERSONAS ----------
        turista = person "Turista" "Visitante que busca tours al valle del Colca, reserva una salida y la paga en línea."
        operador = person "Operador de agencia" "Personal de una agencia de Arequipa o Chivay que publica tours, fechas y cupos, y registra reservas."
        guia = person "Guía de turismo" "Acompaña al grupo en el tour; recibe itinerario y lista de pasajeros por WhatsApp, sin entrar a la plataforma."
        admin = person "Administrador del consorcio" "Aprueba el registro de las agencias y revisa las ventas por agencia y por mes."

        // ---------- SISTEMA EN ESTUDIO ----------
        ruta = softwareSystem "RutaColca" "Plataforma del consorcio donde las agencias publican tours y los turistas los reservan y pagan en línea." {
            web = container "Web del turista" "Búsqueda de tours, reserva de una salida, registro del boleto turístico y pago." "React (SPA)" "WebBrowser"
            portal = container "Portal de agencias" "Publicación de tours, fechas y cupos; reservas presenciales; aprobación de agencias y reportes de ventas." "React (PWA)" "WebBrowser"
            api = container "API de RutaColca" "Reglas de negocio: tours, cupos, reservas, validación del boleto, cobro y envío de itinerarios." "Java 21, Spring Boot"
            bd = container "Base de datos" "Agencias, tours, salidas, cupos, reservas y ventas. No guarda datos de tarjetas." "PostgreSQL 16" "Almacen"
            // Reto HU-05 (Paso 8)
            cache = container "Almacén local sin conexión" "Reservas presenciales pendientes de sincronizar, guardadas en el dispositivo de la agencia." "IndexedDB (navegador)" "Almacen,Reto"
        }

        // ---------- SISTEMAS EXTERNOS ----------
        pasarela = softwareSystem "Pasarela de pagos" "Procesa pagos con tarjeta y billetera móvil; custodia los datos de tarjeta." "Externo"
        whatsapp = softwareSystem "WhatsApp Business" "Entrega mensajes al celular de los guías." "Externo"
        autocolca = softwareSystem "Sistema de AUTOCOLCA" "Emite y valida el boleto turístico del Colca (supuesto didáctico: API HTTPS)." "Externo"

        // ---------- RELACIONES ----------
        turista -> ruta.web "Busca, reserva y paga tours en" "HTTPS"
        operador -> ruta.portal "Publica tours y registra reservas en" "HTTPS"
        admin -> ruta.portal "Aprueba agencias y consulta ventas en" "HTTPS"

        ruta.web -> ruta.api "Consulta tours y envía reservas a" "JSON/HTTPS"
        ruta.portal -> ruta.api "Gestiona tours, cupos y reservas en" "JSON/HTTPS"
        ruta.api -> ruta.bd "Lee y escribe datos en" "JDBC"

        ruta.api -> pasarela "Cobra las reservas en" "HTTPS"
        ruta.api -> autocolca "Valida el boleto turístico en" "HTTPS"
        ruta.api -> whatsapp "Envía itinerarios y listas de grupo mediante" "HTTPS"
        whatsapp -> guia "Entrega el itinerario y la lista del grupo a" "WhatsApp"

        // Reto sin conexión (Paso 8)
        ruta.portal -> ruta.cache "Guarda reservas pendientes en" "API IndexedDB" "Reto"
        ruta.portal -> ruta.api "Sincroniza las reservas hechas sin conexión con" "JSON/HTTPS" "Reto"

        // ---------- DESPLIEGUE (Paso 7) ----------
        prod = deploymentEnvironment "Produccion" {
            celular = deploymentNode "Celular del turista" "Dispositivo del visitante" "Navegador web" {
                webInst = containerInstance ruta.web
            }
            tablet = deploymentNode "Tablet o PC de la agencia" "Equipo de la agencia en Arequipa o Chivay" "Navegador web con PWA instalada" {
                portalInst = containerInstance ruta.portal
                cacheInst = containerInstance ruta.cache
            }
            cloud = deploymentNode "Proveedor cloud" "Infraestructura del consorcio" "Región del proveedor" {
                lb = infrastructureNode "Balanceador de carga" "Termina TLS y reparte el tráfico entre las réplicas de la API." "HTTPS (TLS)"
                vm = deploymentNode "VM Ubuntu 24.04 (x2)" "Dos réplicas para temporada alta (julio, agosto y feriados)" "Ubuntu 24.04" 2 {
                    docker = deploymentNode "Docker" "Entorno de ejecución de la API" "Docker" {
                        apiInst = containerInstance ruta.api
                    }
                }
                dbNode = deploymentNode "PostgreSQL gestionado" "Servicio de base de datos administrado, con respaldos" "PostgreSQL 16" {
                    bdInst = containerInstance ruta.bd
                }
            }
            cloud.lb -> cloud.vm.docker.apiInst "Reparte el tráfico HTTPS hacia" "HTTPS"
        }
    }

    views {
        systemContext ruta "Contexto" "Personas y sistemas externos que se relacionan con RutaColca" {
            title "Diagrama de contexto de RutaColca"
            include *
            include guia
            autolayout lr
        }

        container ruta "Contenedores" "Aplicaciones y almacenes de datos de RutaColca (ADR-001)" {
            title "Diagrama de contenedores de RutaColca"
            include *
            exclude ruta.cache
            exclude "relationship.tag==Reto"
            autolayout tb
        }

        container ruta "ContenedoresReto" "Contenedores incluyendo el almacén local para reservas sin conexión" {
            title "Diagrama de contenedores de RutaColca con soporte sin conexión (HU-05)"
            include *
            autolayout tb
        }

        dynamic ruta "Dinamico" "Reservar y pagar un tour" {
            title "Diagrama dinámico: reservar y pagar un tour (HU-02 y HU-03)"
            turista -> ruta.web "Elige la salida, registra su boleto y confirma el pago"
            ruta.web -> ruta.api "Envía la reserva y el número de boleto"
            ruta.api -> autocolca "Valida el boleto turístico"
            ruta.api -> ruta.bd "Verifica y retiene el cupo de la salida"
            ruta.api -> pasarela "Cobra el monto de la reserva"
            ruta.api -> ruta.bd "Confirma y guarda la reserva"
            ruta.api -> ruta.web "Devuelve la confirmación"
            autolayout lr
        }

        deployment ruta "Produccion" "DespliegueProduccion" "Nodos e instancias de contenedor en el entorno de producción" {
            title "Diagrama de despliegue de RutaColca en producción"
            include *
            autolayout lr
        }

        styles {
            element "Person" {
                shape Person
                background #0B3D62
                color #ffffff
            }
            element "Software System" {
                background #1B5E8B
                color #ffffff
            }
            element "Container" {
                background #0E7D73
                color #ffffff
            }
            element "WebBrowser" {
                shape WebBrowser
            }
            element "Almacen" {
                shape Cylinder
            }
            element "Reto" {
                background #B25E00
                color #ffffff
            }
            element "Externo" {
                background #8C8C8C
                color #ffffff
            }
            relationship "Reto" {
                color #B25E00
                dashed true
            }
        }
    }
}
