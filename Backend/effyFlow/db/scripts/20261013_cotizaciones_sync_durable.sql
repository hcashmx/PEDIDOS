-- COT-MOBILE-05B-2: estado de sincronización ERP -> Sync -> Kotlin.
-- Entregar para revisión; NO ejecutar sobre bases operativas en esta fase.

CREATE TABLE IF NOT EXISTS public.sync_cotizacion_generation (
    usuario_erp_id BIGINT NOT NULL,
    agente_erp_id BIGINT NOT NULL,
    device_id UUID NOT NULL,
    last_generation BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (usuario_erp_id, agente_erp_id, device_id)
);

CREATE TABLE IF NOT EXISTS public.sync_cotizacion_sesion (
    session_id UUID PRIMARY KEY,
    usuario_erp_id BIGINT NOT NULL,
    agente_erp_id BIGINT NOT NULL,
    device_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL CHECK (state IN ('BOOTSTRAP_PENDIENTE','INICIALIZANDO','ACTIVA','REQUIERE_REINICIO','REVOCADA','SESSION_EXPIRADA')),
    bootstrap_started BOOLEAN NOT NULL DEFAULT FALSE,
    start_cursor BIGINT,
    erp_cursor BIGINT,
    max_cotizacion_id BIGINT,
    snapshot_after_id BIGINT NOT NULL DEFAULT 0,
    snapshot_source_complete BOOLEAN NOT NULL DEFAULT FALSE,
    snapshot_complete BOOLEAN NOT NULL DEFAULT FALSE,
    snapshot_acked_sequence BIGINT NOT NULL DEFAULT 0,
    delivery_acked_sequence BIGINT NOT NULL DEFAULT 0,
    delivery_retention_floor BIGINT NOT NULL DEFAULT 1,
    last_offered_sequence BIGINT NOT NULL DEFAULT 0,
    next_delivery_sequence BIGINT NOT NULL DEFAULT 1,
    offline_valid_until TIMESTAMPTZ,
    reason_code VARCHAR(80),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    retry_after TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_client_activity_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_sync_cot_sesion_generation UNIQUE (usuario_erp_id, agente_erp_id, device_id, generation),
    CONSTRAINT ck_sync_cot_cursors CHECK (
        (start_cursor IS NULL AND erp_cursor IS NULL AND max_cotizacion_id IS NULL)
        OR (start_cursor >= 0 AND erp_cursor >= start_cursor AND max_cotizacion_id >= 0)
    ),
    CONSTRAINT ck_sync_cot_delivery_seq CHECK (
        delivery_acked_sequence >= 0 AND delivery_retention_floor >= 1
        AND last_offered_sequence >= delivery_acked_sequence
        AND next_delivery_sequence > last_offered_sequence
    )
);
CREATE INDEX IF NOT EXISTS ix_sync_cot_sesion_worker
    ON public.sync_cotizacion_sesion (state, retry_after, lease_until, updated_at);
CREATE INDEX IF NOT EXISTS ix_sync_cot_sesion_device
    ON public.sync_cotizacion_sesion (device_id, state, updated_at DESC);
CREATE INDEX IF NOT EXISTS ix_sync_cot_sesion_actor
    ON public.sync_cotizacion_sesion (usuario_erp_id, agente_erp_id, state, generation DESC);
CREATE INDEX IF NOT EXISTS ix_sync_cot_sesion_activity
    ON public.sync_cotizacion_sesion (state, last_client_activity_at);

CREATE TABLE IF NOT EXISTS public.sync_cotizacion_snapshot (
    session_id UUID NOT NULL REFERENCES public.sync_cotizacion_sesion(session_id) ON DELETE CASCADE,
    snapshot_ordinal BIGINT NOT NULL,
    cotizacion_uuid UUID NOT NULL,
    erp_id BIGINT NOT NULL,
    estado VARCHAR(80) NOT NULL,
    version BIGINT NOT NULL,
    agente_id BIGINT,
    updated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, snapshot_ordinal),
    CONSTRAINT uq_sync_cot_snapshot_uuid UNIQUE (session_id, cotizacion_uuid)
);

CREATE TABLE IF NOT EXISTS public.sync_cotizacion_event_inbox (
    session_id UUID NOT NULL REFERENCES public.sync_cotizacion_sesion(session_id) ON DELETE CASCADE,
    event_id BIGINT NOT NULL,
    cotizacion_uuid UUID NOT NULL,
    erp_id BIGINT NOT NULL,
    estado VARCHAR(80),
    version BIGINT NOT NULL,
    changed_at TIMESTAMPTZ,
    event_type VARCHAR(80) NOT NULL,
    agente_id BIGINT,
    visible BOOLEAN NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    delivered BOOLEAN NOT NULL DEFAULT FALSE,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, event_id)
);
CREATE INDEX IF NOT EXISTS ix_sync_cot_inbox_pending
    ON public.sync_cotizacion_event_inbox (session_id, delivered, event_id);

CREATE TABLE IF NOT EXISTS public.sync_cotizacion_projection (
    session_id UUID NOT NULL REFERENCES public.sync_cotizacion_sesion(session_id) ON DELETE CASCADE,
    cotizacion_uuid UUID NOT NULL,
    erp_id BIGINT NOT NULL,
    estado VARCHAR(80),
    version BIGINT NOT NULL,
    agente_id BIGINT,
    changed_at TIMESTAMPTZ,
    last_event_id BIGINT NOT NULL DEFAULT 0,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, cotizacion_uuid)
);
CREATE INDEX IF NOT EXISTS ix_sync_cot_projection_visible
    ON public.sync_cotizacion_projection (session_id, visible, cotizacion_uuid);

CREATE TABLE IF NOT EXISTS public.sync_cotizacion_tombstone (
    session_id UUID NOT NULL REFERENCES public.sync_cotizacion_sesion(session_id) ON DELETE CASCADE,
    cotizacion_uuid UUID NOT NULL,
    version BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, cotizacion_uuid)
);

CREATE TABLE IF NOT EXISTS public.sync_cotizacion_delivery (
    session_id UUID NOT NULL REFERENCES public.sync_cotizacion_sesion(session_id) ON DELETE CASCADE,
    delivery_sequence BIGINT NOT NULL,
    item_type VARCHAR(24) NOT NULL CHECK (item_type IN ('SNAPSHOT','SNAPSHOT_END','EVENT')),
    event_id BIGINT,
    cotizacion_uuid UUID,
    erp_id BIGINT,
    estado VARCHAR(80),
    version BIGINT,
    changed_at TIMESTAMPTZ,
    event_type VARCHAR(80),
    agente_id BIGINT,
    visible BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, delivery_sequence),
    CONSTRAINT uq_sync_cot_delivery_event UNIQUE (session_id, event_id),
    CONSTRAINT ck_sync_cot_delivery_payload CHECK (
        (item_type = 'SNAPSHOT_END' AND cotizacion_uuid IS NULL AND event_id IS NULL)
        OR (item_type = 'SNAPSHOT' AND cotizacion_uuid IS NOT NULL AND event_id IS NULL)
        OR (item_type = 'EVENT' AND cotizacion_uuid IS NOT NULL AND event_id IS NOT NULL AND visible IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS ix_sync_cot_delivery_created
    ON public.sync_cotizacion_delivery (session_id, created_at);
CREATE INDEX IF NOT EXISTS ix_sync_cot_snapshot_created
    ON public.sync_cotizacion_snapshot (created_at, session_id);
CREATE INDEX IF NOT EXISTS ix_sync_cot_inbox_received
    ON public.sync_cotizacion_event_inbox (received_at, delivered, session_id);
