Seguimiento de Pedidos por Delivery con Traccar, Camel y Artemis

Extensión del proyecto base traccar-fleet-integration para resolver el Desafío 3: Seguimiento de Pedidos por Delivery por Coordenadas GPS.

El sistema recibe pedidos asignados a repartidores, procesa posiciones GPS producidas por Traccar, calcula la distancia al destino, actualiza automáticamente el estado del pedido y emite notificaciones PUSH simuladas sin duplicados.

Integrantes

Hernan Silgueira

Antonio Aguero

Victor Martinez

Funcionalidades

Registro y consulta de pedidos.

Repartidores precargados en PostgreSQL.

Inicio controlado de una entrega.

Recepción de posiciones GPS mediante Traccar y Apache Camel.

Correlación entre deviceId y el pedido activo del repartidor.

Cálculo geodésico de distancia mediante la fórmula Haversine.

Actualización de la última posición conocida.

Cambio automático de EN_CAMINO a CERCA al ingresar en el radio configurado.

Confirmación de entrega.

Endpoint de tracking para la aplicación cliente.

Notificaciones PUSH simuladas mediante Artemis.

Entrega única de notificaciones por pedido e hito.

Canal de errores para mensajes no procesables.

Pruebas unitarias del cálculo de distancia.

Arquitectura

flowchart TD
    GPS[Dispositivo GPS] --> TR[Traccar]
    TR -->|HTTP JSON| BR[Broker Camel]
    BR -->|vehicle.positions| AR[ActiveMQ Artemis]
    AR --> DS[Delivery Service]
    API[Sistema de pedidos] -->|REST| DS
    DS --> PG[(PostgreSQL)]
    DS -->|delivery.notifications| AR
    AR --> NM[Push Mock]
    DS -->|delivery.errors| AR
    AR --> DLQ[DLQ Mock]
    APP[Aplicación cliente] -->|Tracking REST| DS

Decisión arquitectónica

Se utiliza orquestación. El módulo delivery-service actúa como Process Manager y controla el ciclo de vida del pedido:

RECIBIDO → EN_CAMINO → CERCA → ENTREGADO

Esta decisión centraliza las reglas de transición, la correlación GPS, la persistencia, la generación de hitos y las notificaciones. Los demás componentes permanecen desacoplados y se comunican mediante REST o Artemis.

El flujo GPS y las notificaciones son orientados a eventos. No se utiliza polling sobre PostgreSQL para detectar cambios.

Flujo de negocio

El sistema externo registra un pedido con un repartidor asignado.

El pedido se almacena en estado RECIBIDO.

PUT /pedidos/{id}/iniciar cambia el estado a EN_CAMINO.

Se genera PEDIDO_EN_CAMINO y una notificación PUSH simulada.

Traccar publica posiciones del repartidor.

El broker transforma el payload al modelo canónico VehiclePosition.

delivery-service correlaciona deviceId con el pedido activo.

Se calcula la distancia al destino y se actualiza pedido_ultima_posicion.

Si la distancia es menor o igual al radio configurado, el pedido pasa a CERCA.

Se genera PEDIDO_CERCA y una notificación única.

PUT /pedidos/{id}/entregar cambia el pedido a ENTREGADO.

Se registra PEDIDO_ENTREGADO y se emite la notificación final.

Tecnologías

Java 21

Apache Camel 4.8.0

Gradle Wrapper 9.6.1

ActiveMQ Artemis con AMQP 1.0

Qpid JMS

PostgreSQL 17

Traccar

Docker y Docker Compose

JUnit 5

Patrones EIP aplicados

Patrón

Ubicación

Problema resuelto

Messaging Gateway

broker

Expone HTTP y desacopla Traccar de Artemis.

Content-Based Router

IngestRoute

Clasifica posiciones y eventos.

Message Translator

Traductores del broker

Convierte el formato Traccar al modelo canónico.

Canonical Data Model

common

Define VehiclePosition y VehicleEvent.

Publish-Subscribe Channel

vehicle.positions

Distribuye cada posición a consumidores independientes.

Durable Subscriber

Consumidores AMQP

Conserva las suscripciones durante desconexiones.

Message Filter

Procesamiento GPS

Rechaza posiciones inválidas.

Correlation Identifier

pedidoId, deviceId, messageId

Relaciona pedido, repartidor, posición y evento.

Process Manager

delivery-service

Orquesta el ciclo de vida del pedido.

Idempotent Receiver

pedido_eventos

Evita hitos y notificaciones duplicadas.

Dead Letter Channel

delivery.errors

Recibe mensajes que no pueden procesarse.

Wire Tap

Notificaciones y errores

Envía copias asíncronas sin bloquear el flujo principal.

Canales Artemis

Canal

Tipo

Productor

Consumidor

Responsabilidad

ingest

Queue

Broker HTTP

IngestRoute

Entrada de forwards de Traccar.

vehicle.positions

Topic

Broker

Consumers y delivery-service

Posiciones GPS canónicas.

vehicle.events

Topic

Broker

events-consumer

Eventos canónicos de Traccar.

delivery.notifications

Queue

delivery-service

PUSH mock

Notificaciones de hitos.

delivery.errors

Queue

delivery-service

DLQ mock

Mensajes no procesables.

Persistencia

PostgreSQL contiene las siguientes tablas:

repartidores: datos maestros precargados.

pedidos: estado y datos de entrega.

pedido_ultima_posicion: última posición conocida y distancia calculada.

pedido_eventos: bitácora de hitos y control de notificaciones.

Se conserva únicamente la última posición de cada pedido. Esta decisión reduce el crecimiento de la base y satisface la consulta de tracking. El flujo completo continúa disponible temporalmente en Artemis y en los logs.

La restricción única (pedido_id, hito) impide duplicar eventos. También existe una restricción parcial que permite solamente un pedido activo por repartidor.

Estrategia de notificación única

Antes de publicar una notificación se ejecuta una operación atómica en PostgreSQL:

Se busca el evento con notificado=false.

Se actualiza a notificado=true mediante UPDATE ... RETURNING.

Solo la ejecución que obtiene la fila publica en delivery.notifications.

Los reintentos posteriores no recuperan filas y se omiten.

Esta estrategia garantiza como máximo una notificación por pedido e hito, incluso si una posición GPS se reenvía.

El PUSH se simula con un consumidor que registra en logs el pedido, hito, título, cuerpo, MSISDN y token FCM. El envío real a FCM/APNs queda fuera del alcance.

Manejo de errores

Las posiciones que no pueden correlacionarse o procesarse se publican en delivery.errors con:

motivo;

pedidoId, si existe;

deviceId;

coordenadas;

timestamp de la posición;

timestamp del error.

Un consumidor DLQ simulado deja evidencia del mensaje. Los errores REST utilizan códigos 400, 404, 409 o 500, según corresponda.

Puertos

Servicio

Puerto

Broker HTTP

8080

Delivery Service

8081

Traccar Web/API

8082

Traccar OsmAnd

5055

Artemis AMQP

5672

Artemis Core/OpenWire

61616

Artemis MQTT

1883

Artemis Console

8161

PostgreSQL host

5433

Requisitos previos

JDK 21.

Docker Desktop.

Docker Compose.

Git.

Windows PowerShell o una terminal compatible.

Variables de entorno

Valores predeterminados para desarrollo:

ARTEMIS_AMQP_URL=amqp://artemis:5672
ARTEMIS_USER=admin
ARTEMIS_PASSWORD=admin123
DB_URL=jdbc:postgresql://postgres:5432/delivery
DB_USER=delivery
DB_PASSWORD=delivery123

En PowerShell debe existir HOME para compartir el caché Maven con Docker:

$env:HOME = $env:USERPROFILE

Compilación y ejecución

git clone https://github.com/hernansilgueira-ccp/traccar-delivery-integration.git
cd traccar-delivery-integration
$env:HOME = $env:USERPROFILE
.\gradlew.bat clean test
docker compose config --quiet
docker compose build
docker compose up -d
docker compose ps

Health check:

Invoke-RestMethod -Uri "http://localhost:8081/health" -Method Get

Resultado:

{
  "status": "UP",
  "database": "UP"
}

API REST

Crear pedido

POST /pedidos
Content-Type: application/json

{
  "id": "PED-004",
  "clienteNombre": "Cliente Demo",
  "clienteMsisdn": "+595981000000",
  "clienteFcmId": "fcm-token-demo",
  "direccionTexto": "Asunción",
  "latDestino": -25.2967,
  "lonDestino": -57.6359,
  "radioLlegadaM": 150,
  "repartidorDeviceId": "repartidor-01"
}

Respuesta exitosa: 201 Created con estado RECIBIDO.

Listar pedidos

GET /pedidos

Consultar pedido

GET /pedidos/{id}

Iniciar entrega

PUT /pedidos/{id}/iniciar

Transición permitida: RECIBIDO → EN_CAMINO.

Consultar tracking

GET /pedidos/{id}/tracking

{
  "pedidoId": "PED-004",
  "estado": "CERCA",
  "posicion": {
    "lat": -25.2967,
    "lon": -57.6359,
    "velocidadKmh": 18.52,
    "distanciaDestinoM": 0.0,
    "timestamp": 1789604886279
  }
}

posicion es null si todavía no existen reportes GPS.

Confirmar entrega

PUT /pedidos/{id}/entregar

Transición permitida: CERCA → ENTREGADO.

Guía de prueba funcional

1. Crear pedido

$pedido = @{
    id = "PED-004"
    clienteNombre = "Cliente Demo"
    clienteMsisdn = "+595981000000"
    clienteFcmId = "fcm-token-demo"
    direccionTexto = "Asuncion"
    latDestino = -25.2967
    lonDestino = -57.6359
    radioLlegadaM = 150
    repartidorDeviceId = "repartidor-01"
} | ConvertTo-Json

Invoke-RestMethod `
    -Uri "http://localhost:8081/pedidos" `
    -Method Post `
    -ContentType "application/json" `
    -Body $pedido

2. Iniciar entrega

Invoke-RestMethod `
    -Uri "http://localhost:8081/pedidos/PED-004/iniciar" `
    -Method Put

3. Enviar posición GPS

$fixTime = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

$posicion = @{
    position = @{
        deviceId = "repartidor-01"
        latitude = -25.2967
        longitude = -57.6359
        speed = 10
        course = 90
        valid = $true
        fixTime = $fixTime
        attributes = @{ ignition = $true }
    }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
    -Uri "http://localhost:8080/traccar/ingest" `
    -Method Post `
    -ContentType "application/json" `
    -Body $posicion

4. Consultar tracking

Invoke-RestMethod `
    -Uri "http://localhost:8081/pedidos/PED-004/tracking" `
    -Method Get |
    ConvertTo-Json -Depth 5

5. Confirmar entrega

Invoke-RestMethod `
    -Uri "http://localhost:8081/pedidos/PED-004/entregar" `
    -Method Put

6. Verificar eventos e idempotencia

docker exec postgres-delivery `
    psql -U delivery -d delivery `
    -c "SELECT pedido_id, hito, notificado, timestamp FROM pedido_eventos WHERE pedido_id='PED-004' ORDER BY id;"

7. Probar canal de errores

Enviar una posición con deviceId=repartidor-99 y revisar:

docker compose logs --since=2m delivery-service |
    Select-String -Pattern "DLQ MOCK|REPARTIDOR_SIN_PEDIDO_ACTIVO"

Pruebas automatizadas

.\gradlew.bat clean test

Actualmente se incluyen cinco pruebas unitarias para:

distancia cero;

cálculo de una posición lejana;

simetría de distancia;

posición dentro del radio de 150 metros;

posición fuera del radio.

Resultado esperado:

BUILD SUCCESSFUL
Pruebas: 5
Fallos: 0
Errores: 0

Evidencias recomendadas

Guardar en docs/evidencias/:

01-build-successful.png

02-docker-compose-ps.png

03-pedido-recibido.png

04-posicion-lejana.png

05-pedido-cerca.png

06-push-mock.png

07-pedido-entregado.png

08-eventos-idempotentes.png

09-tracking-endpoint.png

10-delivery-errors.png

Estructura principal

traccar-delivery-integration/
├── broker/
├── common/
├── delivery-service/
├── events-consumer/
├── positions-consumer/
├── database/init/
├── traccar/
├── docs/evidencias/
├── compose.yaml
├── build.gradle
├── settings.gradle
└── README.md

Limitaciones conocidas

El PUSH es simulado mediante un consumidor Artemis y logs; no se conecta a FCM/APNs.

Se almacena únicamente la última posición por pedido, no todo el histórico GPS.

El inicio y la confirmación final se realizan mediante endpoints REST.

Los timestamps JDBC se serializan actualmente como milisegundos Unix en algunas respuestas.

Las credenciales predeterminadas son solo para desarrollo académico.

La DLQ posee un consumidor mock inmediato; la evidencia se conserva en logs.

Detener el entorno

Sin eliminar datos:

docker compose down

Eliminando también el volumen de PostgreSQL:

docker compose down -v

Referencias

Apache Camel

Enterprise Integration Patterns

ActiveMQ Artemis

Traccar Forwarding

PostgreSQL

Proyecto base

Licencia

Proyecto académico desarrollado para la materia Integración de Sistemas II de la UCOM.