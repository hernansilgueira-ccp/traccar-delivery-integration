CREATE TABLE repartidores (
    device_id VARCHAR(64) PRIMARY KEY,
    nombre VARCHAR(120) NOT NULL,
    msisdn VARCHAR(20)
);

CREATE TABLE pedidos (
    id VARCHAR(50) PRIMARY KEY,
    cliente_nombre VARCHAR(120) NOT NULL,
    cliente_msisdn VARCHAR(20) NOT NULL,
    cliente_fcm_id VARCHAR(255),
    direccion_texto VARCHAR(255),
    lat_destino DOUBLE PRECISION NOT NULL
        CHECK (lat_destino BETWEEN -90 AND 90),
    lon_destino DOUBLE PRECISION NOT NULL
        CHECK (lon_destino BETWEEN -180 AND 180),
    radio_llegada_m INTEGER NOT NULL DEFAULT 150
        CHECK (radio_llegada_m > 0),
    repartidor_device_id VARCHAR(64) NOT NULL
        REFERENCES repartidores(device_id),
    estado VARCHAR(20) NOT NULL DEFAULT 'RECIBIDO'
        CHECK (
            estado IN (
                'RECIBIDO',
                'EN_CAMINO',
                'CERCA',
                'ENTREGADO',
                'CANCELADO'
            )
        ),
    fecha_creacion TIMESTAMPTZ NOT NULL DEFAULT now(),
    fecha_actualizacion TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pedido_ultima_posicion (
    pedido_id VARCHAR(50) PRIMARY KEY
        REFERENCES pedidos(id) ON DELETE CASCADE,
    device_id VARCHAR(64) NOT NULL,
    lat DOUBLE PRECISION NOT NULL
        CHECK (lat BETWEEN -90 AND 90),
    lon DOUBLE PRECISION NOT NULL
        CHECK (lon BETWEEN -180 AND 180),
    velocidad_kmh DOUBLE PRECISION,
    distancia_destino_m DOUBLE PRECISION,
    timestamp TIMESTAMPTZ NOT NULL
);

CREATE TABLE pedido_eventos (
    id BIGSERIAL PRIMARY KEY,
    pedido_id VARCHAR(50) NOT NULL
        REFERENCES pedidos(id) ON DELETE CASCADE,
    hito VARCHAR(30) NOT NULL,
    detalle JSONB,
    notificado BOOLEAN NOT NULL DEFAULT FALSE,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (pedido_id, hito)
);

CREATE INDEX idx_pedidos_repartidor
    ON pedidos(repartidor_device_id);

CREATE INDEX idx_pedidos_estado
    ON pedidos(estado);

CREATE INDEX idx_pedido_eventos_pedido
    ON pedido_eventos(pedido_id);

CREATE UNIQUE INDEX uq_pedido_activo_repartidor
    ON pedidos(repartidor_device_id)
    WHERE estado IN ('RECIBIDO', 'EN_CAMINO', 'CERCA');

INSERT INTO repartidores (device_id, nombre, msisdn)
VALUES
    ('repartidor-01', 'Carlos Gómez', '+595981111111'),
    ('repartidor-02', 'María López', '+595982222222');