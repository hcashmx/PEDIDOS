BEGIN;

-- Adds synchronization timestamps for ERP -> Kotlin catalog sync.
-- This script is intentionally additive: no drops, no data deletion, no table rebuilds.
-- Existing rows are initialized with CURRENT_TIMESTAMP so the first full sync can include them.
-- PostgreSQL triggers keep updated_at current for writes coming from JPA, imports, or direct SQL.

CREATE OR REPLACE FUNCTION public.set_sync_timestamps()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.created_at := COALESCE(NEW.created_at, clock_timestamp());
        NEW.updated_at := COALESCE(NEW.updated_at, NEW.created_at, clock_timestamp());
    ELSE
        NEW.created_at := OLD.created_at;
        NEW.updated_at := clock_timestamp();
    END IF;

    RETURN NEW;
END;
$$;

ALTER TABLE public.agentes ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.agentes ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.agentes SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_agentes BEFORE INSERT OR UPDATE ON public.agentes FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.categorias ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.categorias ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.categorias SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_categorias BEFORE INSERT OR UPDATE ON public.categorias FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.clientes ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.clientes ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.clientes SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_clientes BEFORE INSERT OR UPDATE ON public.clientes FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.clientes_ubicaciones ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.clientes_ubicaciones ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.clientes_ubicaciones SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_clientes_ubicaciones BEFORE INSERT OR UPDATE ON public.clientes_ubicaciones FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.productos ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.productos ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.productos SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_productos BEFORE INSERT OR UPDATE ON public.productos FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.precios_producto ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.precios_producto ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.precios_producto SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_precios_producto BEFORE INSERT OR UPDATE ON public.precios_producto FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.existencias ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.existencias ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.existencias SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_existencias BEFORE INSERT OR UPDATE ON public.existencias FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.series_folios ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.series_folios ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.series_folios SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_series_folios BEFORE INSERT OR UPDATE ON public.series_folios FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.series_folios_usuarios ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.series_folios_usuarios ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.series_folios_usuarios SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_series_folios_usuarios BEFORE INSERT OR UPDATE ON public.series_folios_usuarios FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.impuestos ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.impuestos ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.impuestos SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_impuestos BEFORE INSERT OR UPDATE ON public.impuestos FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.productos_impuestos ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.productos_impuestos ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.productos_impuestos SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_productos_impuestos BEFORE INSERT OR UPDATE ON public.productos_impuestos FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.unidades_medida ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.unidades_medida ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.unidades_medida SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_unidades_medida BEFORE INSERT OR UPDATE ON public.unidades_medida FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.marcas ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.marcas ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.marcas SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_marcas BEFORE INSERT OR UPDATE ON public.marcas FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.productos_sustitutos ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.productos_sustitutos ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.productos_sustitutos SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_productos_sustitutos BEFORE INSERT OR UPDATE ON public.productos_sustitutos FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

ALTER TABLE public.kits_detalle ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.kits_detalle ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.kits_detalle SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_kits_detalle BEFORE INSERT OR UPDATE ON public.kits_detalle FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

-- users already had created_at/updated_at and participates in login/catalog sync.
-- The trigger makes its updated_at reliable without adding functional ERP fields.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP;
UPDATE public.users SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL OR updated_at IS NULL;
CREATE OR REPLACE TRIGGER trg_sync_timestamps_users BEFORE INSERT OR UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.set_sync_timestamps();

COMMIT;
