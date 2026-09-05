package com.cyssoftware.effyFlow.service.pedido;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SyncService {

    private final NamedParameterJdbcTemplate jdbc;

    public Map<String, Object> obtenerCatalogo(String nombre, LocalDateTime updatedAfter, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);

        CatalogQuery catalog = catalogQuery(nombre.toLowerCase());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safeSize)
                .addValue("offset", safePage * safeSize);

        String where = "";
        if (updatedAfter != null && catalog.incremental()) {
            where = " WHERE " + catalog.updatedColumn() + " > :updatedAfter";
            params.addValue("updatedAfter", Timestamp.valueOf(updatedAfter));
        }

        Long totalElements = jdbc.queryForObject("SELECT COUNT(*) FROM " + catalog.fromClause() + where, params, Long.class);
        List<Map<String, Object>> data = jdbc.queryForList(
                catalog.selectSql() + where + " ORDER BY " + catalog.orderBy() + " LIMIT :limit OFFSET :offset",
                params).stream().map(this::normalizeTemporalValues).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", safePage);
        result.put("totalPages", totalElements == null ? 0 : (int) Math.ceil(totalElements / (double) safeSize));
        result.put("totalElements", totalElements == null ? 0 : totalElements);
        result.put("data", data);
        return result;
    }

    private CatalogQuery catalogQuery(String nombre) {
        return switch (nombre) {
            case "agentes" -> new CatalogQuery("""
                    SELECT
                        id_agente AS "idAgente",
                        nombre,
                        id_usuario AS "idUsuario",
                        tipo_agente_id AS "tipoAgenteId",
                        updated_at AS "updatedAt"
                    FROM public.agentes
                    """, "public.agentes", "updated_at", "id_agente", true);
            case "categorias" -> new CatalogQuery("""
                    SELECT
                        id_categoria AS "idCategoria",
                        nombre_categoria AS "nombreCategoria",
                        id_padre AS "idPadre",
                        nivel,
                        activo,
                        updated_at AS "updatedAt"
                    FROM public.categorias
                    """, "public.categorias", "updated_at", "id_categoria", true);
            case "clientes" -> new CatalogQuery("""
                    SELECT
                        id_cliente AS "idCliente",
                        nombre,
                        rfc,
                        razon_social AS "razonSocial",
                        email,
                        telefono,
                        direccion,
                        codigo_postal AS "codigoPostal",
                        localidad,
                        municipio,
                        estado,
                        pais,
                        uso_cfdi_id::text AS "usoCfdi",
                        NULL::text AS "metodoPagoSat",
                        forma_pago_sat AS "formaPagoSat",
                        activo,
                        numero_precio_id AS "numeroPrecioId",
                        limite_credito AS "limiteCredito",
                        credito_bloqueado AS "creditoBloqueado",
                        motivo_bloqueo_credito AS "motivoBloqueoCredito",
                        fecha_bloqueo_credito AS "fechaBloqueoCredito",
                        agente_id AS "agenteId",
                        tipo_clente_id AS "tipoClenteId",
                        ruta_id AS "rutaId",
                        plazo,
                        updated_at AS "updatedAt"
                    FROM public.clientes
                    """, "public.clientes", "updated_at", "id_cliente", true);
            case "clientes_ubicaciones" -> new CatalogQuery("""
                    SELECT
                        id_cliente_ubicacion AS "idClienteUbicacion",
                        id_cliente AS "idCliente",
                        alias,
                        activo,
                        contacto_persona AS "contactoPersona",
                        contacto_telefono AS "contactoTelefono",
                        direccion AS "direcccion",
                        no_exterior AS "noExterior",
                        no_interior AS "noInterior",
                        colonia,
                        localidad,
                        municipio,
                        estado,
                        pais,
                        codigo_postal AS "codigoPostal",
                        referencia,
                        latitud,
                        longitud,
                        updated_at AS "updatedAt"
                    FROM public.clientes_ubicaciones
                    """, "public.clientes_ubicaciones", "updated_at", "id_cliente_ubicacion", true);
            case "productos" -> new CatalogQuery("""
                    SELECT
                        id_producto AS "idProducto",
                        codigo,
                        nombre,
                        descripcion,
                        activo,
                        id_categoria AS "idCategoria",
                        upc,
                        largo,
                        ancho,
                        alto,
                        grueso,
                        peso,
                        tipo,
                        clave_sat AS "claveSat",
                        id_unidad AS "idUnidad",
                        costo_ultimo AS "costoUltimo",
                        costo_reposicion AS "costoReposicion",
                        costo_anterior AS "costoAnterior",
                        costo_promedio AS "costoPromedio",
                        descuento_maximo AS "descuentoMax",
                        tipo_articulo AS "tipoArticulo",
                        id_marca AS "idMarca",
                        id_medida AS "idMedida",
                        permite_negativo AS "permiteNegativo",
                        "inner",
                        master,
                        updated_at AS "updatedAt"
                    FROM public.productos
                    """, "public.productos", "updated_at", "id_producto", true);
            case "precios_producto" -> new CatalogQuery("""
                    SELECT
                        producto_id AS "productoId",
                        tipo_precio_id AS "tipoPrecioId",
                        precio,
                        updated_at AS "updatedAt"
                    FROM public.precios_producto
                    """, "public.precios_producto", "updated_at", "producto_id, tipo_precio_id", true);
            case "stock_productos" -> new CatalogQuery("""
                    SELECT
                        almacen_id AS "idUbicacion",
                        producto_id AS "idProducto",
                        cantidad_disponible AS cantidad,
                        updated_at AS "updatedAt"
                    FROM public.existencias
                    """, "public.existencias", "updated_at", "producto_id, almacen_id", true);
            case "series_folios" -> new CatalogQuery("""
                    SELECT
                        id_serie AS "idSerie",
                        codigo_serie AS "codigoSerie",
                        descripcion,
                        tipo_movimiento_id AS "tipoMovimientoId",
                        NULL::integer AS "tipoMovimientoInventario",
                        siguiente_folio AS folio,
                        formato_impresion AS "formatoImpresion",
                        serie_devolucion_id AS "serieDevolucionId",
                        activa,
                        es_consecutiva AS "esConsecutiva",
                        'PEDIDO'::text AS "tipoSerie",
                        updated_at AS "updatedAt"
                    FROM public.series_folios
                    """, "public.series_folios", "updated_at", "id_serie", true);
            case "series_folios_usuarios" -> new CatalogQuery("""
                    SELECT
                        serie_id AS "serieId",
                        usuario_id AS "usuarioId",
                        puede_usar AS "puedeUsar",
                        updated_at AS "updatedAt"
                    FROM public.series_folios_usuarios
                    """, "public.series_folios_usuarios", "updated_at", "serie_id, usuario_id", true);
            case "usuarios" -> new CatalogQuery("""
                    SELECT
                        id,
                        nombre,
                        email,
                        role AS roles,
                        status,
                        updated_at AS "updatedAt"
                    FROM public.users
                    """, "public.users", "updated_at", "id", true);
            case "unidades_medida" -> new CatalogQuery("""
                    SELECT
                        id_unidad AS "idUnidad",
                        nombre,
                        clave_sat AS "claveSat",
                        abreviatura,
                        updated_at AS "updatedAt"
                    FROM public.unidades_medida
                    """, "public.unidades_medida", "updated_at", "id_unidad", true);
            case "impuestos" -> new CatalogQuery("""
                    SELECT
                        id_impuesto AS "idImpuesto",
                        nombre,
                        tipo_aplicacion AS "tipoAplicacion",
                        tipo_factor AS "tipoFactor",
                        tasa_o_cuota AS "tasaOCuota",
                        unidad_cuota AS "unidadCuota",
                        c_impuesto AS cimpuesto,
                        nombre_sat AS "nombreSat",
                        signo,
                        activo,
                        updated_at AS "updatedAt"
                    FROM public.impuestos
                    """, "public.impuestos", "updated_at", "id_impuesto", true);
            case "productos_impuestos" -> new CatalogQuery("""
                    SELECT
                        id_producto AS "idProducto",
                        id_impuesto AS "idImpuesto",
                        orden,
                        base_incluye_impuestos_previos AS "baseIncluyeImpuestosPrevios",
                        aplica_descuentos_en_base AS "aplicaDescuentosEnBase",
                        multiplicador_unidades AS "multiplicadorUnidades",
                        updated_at AS "updatedAt"
                    FROM public.productos_impuestos
                    """, "public.productos_impuestos", "updated_at", "id_producto, id_impuesto", true);
            case "presentaciones_producto" -> new CatalogQuery("""
                    SELECT
                        id_presentacion AS "idPresentacion",
                        id_producto AS "idProducto",
                        id_unidad AS "idUnidad",
                        descripcion,
                        factor_conversion AS "factorConversion",
                        cantidad_base AS "cantidadBase",
                        es_unidad_venta AS "esUnidadVenta",
                        es_predeterminada AS "esPredeterminada",
                        NULL::timestamp AS "updatedAt"
                    FROM public.presentaciones_producto
                    """, "public.presentaciones_producto", "id_presentacion", "id_presentacion", false);
            case "productos_sustitutos" -> new CatalogQuery("""
                    SELECT
                        id_producto_sustituto AS "idProductoSustituto",
                        producto_id AS "productoId",
                        producto_sustituto_id AS "productoSustitutoId",
                        prioridad,
                        motivo,
                        vigente,
                        observaciones,
                        updated_at AS "updatedAt"
                    FROM public.productos_sustitutos
                    """, "public.productos_sustitutos", "updated_at", "id_producto_sustituto", true);
            case "marcas" -> new CatalogQuery("""
                    SELECT
                        id_marca AS "idMarca",
                        nombre,
                        updated_at AS "updatedAt"
                    FROM public.marcas
                    """, "public.marcas", "updated_at", "id_marca", true);
            case "kits_detalle" -> new CatalogQuery("""
                    SELECT
                        id_kit_detalle AS "idKitDetalle",
                        id_producto_kit AS "idProductoKit",
                        id_producto_componente AS "idProductoComponente",
                        cantidad,
                        orden,
                        created_at AS "createdAt",
                        updated_at AS "updatedAt"
                    FROM public.kits_detalle
                    """, "public.kits_detalle", "updated_at", "id_kit_detalle", true);
            case "pedidos" -> new CatalogQuery("""
                    SELECT
                        id_pedido AS "idPedido",
                        serie_id AS "serieId",
                        folio,
                        estado_id AS "estadoId",
                        fecha_creacion::timestamp AS "updatedAt"
                    FROM public.pedidos
                    """, "public.pedidos", "fecha_creacion", "id_pedido", true);
            case "detalle_pedidos" -> new CatalogQuery("""
                    SELECT
                        id_detalle_pedido AS "idDetallePedido",
                        pedido_id AS "pedidoId",
                        producto_id AS "productoId",
                        presentacion_id AS "presentacionId",
                        almacen_id AS "almacenId",
                        cantidad_solicitada AS "cantidadSolicitada",
                        cantidad_autorizada AS "cantidadAutorizada",
                        cantidad_reservada AS "cantidadReservada",
                        cantidad_surtida AS "cantidadSurtida",
                        cantidad_cancelada AS "cantidadCancelada",
                        precio_unitario AS "precioUnitario",
                        descuento,
                        impuestos::text AS impuestos,
                        total_linea AS "totalLinea",
                        estado_linea AS "estadoLinea",
                        NULL::timestamp AS "updatedAt"
                    FROM public.detalle_pedidos
                    """, "public.detalle_pedidos", "id_detalle_pedido", "id_detalle_pedido", false);
            default -> throw new IllegalArgumentException("Catalogo no reconocido: " + nombre);
        };
    }

    private Map<String, Object> normalizeTemporalValues(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>(row.size());
        row.forEach((key, value) -> {
            if (value instanceof Timestamp timestamp) {
                normalized.put(key, timestamp.toLocalDateTime().toString());
            } else if (value instanceof java.sql.Date date) {
                normalized.put(key, date.toLocalDate().atStartOfDay().toString());
            } else {
                normalized.put(key, value);
            }
        });
        return normalized;
    }

    private record CatalogQuery(
            String selectSql,
            String fromClause,
            String updatedColumn,
            String orderBy,
            boolean incremental) {
    }
}
