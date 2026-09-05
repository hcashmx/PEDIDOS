BEGIN;

ALTER TABLE public.pedidos
    ADD COLUMN IF NOT EXISTS uuid_local varchar(36);

CREATE SEQUENCE IF NOT EXISTS public.pedidos_id_pedido_seq AS bigint
    OWNED BY public.pedidos.id_pedido;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'pedidos'
          AND column_name = 'id_pedido'
          AND is_identity = 'NO'
    ) THEN
        PERFORM setval(
            'public.pedidos_id_pedido_seq',
            COALESCE((SELECT MAX(id_pedido) FROM public.pedidos), 0) + 1,
            false
        );

        ALTER TABLE public.pedidos
            ALTER COLUMN id_pedido SET DEFAULT nextval('public.pedidos_id_pedido_seq');
    END IF;
END $$;

CREATE SEQUENCE IF NOT EXISTS public.detalle_pedidos_id_detalle_pedido_seq AS bigint
    OWNED BY public.detalle_pedidos.id_detalle_pedido;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'detalle_pedidos'
          AND column_name = 'id_detalle_pedido'
          AND is_identity = 'NO'
    ) THEN
        PERFORM setval(
            'public.detalle_pedidos_id_detalle_pedido_seq',
            COALESCE((SELECT MAX(id_detalle_pedido) FROM public.detalle_pedidos), 0) + 1,
            false
        );

        ALTER TABLE public.detalle_pedidos
            ALTER COLUMN id_detalle_pedido SET DEFAULT nextval('public.detalle_pedidos_id_detalle_pedido_seq');
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_pedidos_uuid_local_not_null
    ON public.pedidos (uuid_local)
    WHERE uuid_local IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_pedidos_serie_folio
    ON public.pedidos (serie_id, folio);

COMMIT;
