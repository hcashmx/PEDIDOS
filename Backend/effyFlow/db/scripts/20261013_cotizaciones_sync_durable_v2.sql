-- Upgrade controlado de COT-MOBILE-05B-2. Ejecutar una vez sobre el esquema 05B-2,
-- con backup y workers detenidos. No apuntar a una base compartida sin revisión previa.
BEGIN;

CREATE TABLE public.sync_cotizacion_event_ledger (
    session_id UUID NOT NULL REFERENCES public.sync_cotizacion_sesion(session_id) ON DELETE CASCADE,
    event_id BIGINT NOT NULL CHECK (event_id > 0),
    payload_sha256 CHAR(64) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, event_id)
);

-- Conservar hashes ya presentes. No se pueden reconstruir filas inbox que la retención
-- anterior haya borrado; sesiones antiguas deben reiniciarse antes de reanudar el worker.
INSERT INTO public.sync_cotizacion_event_ledger(session_id,event_id,payload_sha256,applied_at)
SELECT session_id,event_id,payload_sha256,received_at
FROM public.sync_cotizacion_event_inbox
ON CONFLICT (session_id,event_id) DO NOTHING;

ALTER TABLE public.sync_cotizacion_sesion
    ADD COLUMN queue_saturated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN queue_saturated_at TIMESTAMPTZ;

-- The previous 30-day inbox retention may have removed older hashes. Retire every
-- pre-v2 live generation so the next authorized client call performs a clean resync.
UPDATE public.sync_cotizacion_sesion
SET state='REQUIERE_REINICIO',reason_code='LEDGER_MIGRATION_REQUIRED',
    offline_valid_until=NULL,queue_saturated=FALSE,queue_saturated_at=NULL,lease_owner=NULL,lease_until=NULL,
    updated_at=CURRENT_TIMESTAMP,row_version=row_version+1
WHERE state IN ('BOOTSTRAP_PENDIENTE','INICIALIZANDO','ACTIVA');

ALTER TABLE public.sync_cotizacion_generation
    ADD CONSTRAINT ck_sync_cot_gen_domain CHECK (usuario_erp_id > 0 AND agente_erp_id > 0 AND last_generation > 0);
ALTER TABLE public.sync_cotizacion_sesion
    ADD CONSTRAINT ck_sync_cot_session_domain CHECK (
        usuario_erp_id > 0 AND agente_erp_id > 0 AND generation > 0
        AND snapshot_after_id >= 0 AND snapshot_acked_sequence >= 0
        AND snapshot_acked_sequence <= delivery_acked_sequence
        AND delivery_retention_floor <= next_delivery_sequence
        AND retry_count >= 0 AND row_version >= 0
    ),
    ADD CONSTRAINT ck_sync_cot_session_state_cursor CHECK (
        state NOT IN ('INICIALIZANDO','ACTIVA')
        OR (start_cursor IS NOT NULL AND erp_cursor IS NOT NULL AND max_cotizacion_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_sync_cot_snapshot_state CHECK (NOT snapshot_complete OR snapshot_source_complete),
    ADD CONSTRAINT ck_sync_cot_active_snapshot_ack CHECK (
        state <> 'ACTIVA' OR (snapshot_complete AND delivery_acked_sequence >= snapshot_acked_sequence)
    ),
    ADD CONSTRAINT ck_sync_cot_queue_saturation CHECK (
        (queue_saturated AND queue_saturated_at IS NOT NULL)
        OR (NOT queue_saturated AND queue_saturated_at IS NULL)
    );
ALTER TABLE public.sync_cotizacion_snapshot
    ADD CONSTRAINT ck_sync_cot_snapshot_domain CHECK (
        snapshot_ordinal > 0 AND erp_id > 0 AND version >= 0 AND (agente_id IS NULL OR agente_id > 0)
    );
ALTER TABLE public.sync_cotizacion_event_inbox
    ADD CONSTRAINT ck_sync_cot_inbox_domain CHECK (
        event_id > 0 AND erp_id > 0 AND version >= 0 AND (agente_id IS NULL OR agente_id > 0)
        AND (visible OR (estado IS NULL AND agente_id IS NULL))
    );
ALTER TABLE public.sync_cotizacion_projection
    ADD CONSTRAINT ck_sync_cot_projection_domain CHECK (
        erp_id > 0 AND version >= 0 AND last_event_id >= 0 AND (agente_id IS NULL OR agente_id > 0)
        AND (visible OR (estado IS NULL AND agente_id IS NULL))
    );
ALTER TABLE public.sync_cotizacion_tombstone
    ADD CONSTRAINT ck_sync_cot_tombstone_domain CHECK (version >= 0 AND event_id > 0);
ALTER TABLE public.sync_cotizacion_delivery
    ADD CONSTRAINT ck_sync_cot_delivery_domain CHECK (
        delivery_sequence > 0 AND (erp_id IS NULL OR erp_id > 0)
        AND (version IS NULL OR version >= 0) AND (agente_id IS NULL OR agente_id > 0)
        AND (item_type <> 'EVENT' OR visible OR (estado IS NULL AND agente_id IS NULL))
    );

CREATE INDEX ix_sync_cot_event_ledger_applied
    ON public.sync_cotizacion_event_ledger (session_id, applied_at);

COMMIT;
