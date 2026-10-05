package com.cyssoftware.effyFlow.service.pedido;

import com.cyssoftware.effyFlow.dto.PedidoEstadosBatch;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PedidoEstadosService {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String SQL = """
            SELECT h.id_historial, h.pedido_id, p.uuid_local,
                   h.estado_nuevo AS estado_id, e.estado,
                   h.fecha_cambio::text AS fecha_cambio
            FROM public.pedidos_estados_historial h
            JOIN public.pedidos p ON p.id_pedido = h.pedido_id
            JOIN public.pedido_estados e ON e.id_estado = h.estado_nuevo
            WHERE h.id_historial > :afterId AND h.id_historial <= :untilId
            ORDER BY h.id_historial ASC
            LIMIT :limit
            """;

    @Transactional(readOnly = true)
    public PedidoEstadosBatch obtener(long afterId, Long untilId, int size) {
        if (afterId < 0 || (untilId != null && untilId < afterId)) {
            throw new IllegalArgumentException("Cursor de historial invalido");
        }
        int limit = Math.min(500, Math.max(1, size));
        Long maximum = untilId != null ? untilId : jdbc.queryForObject(
                "SELECT COALESCE(MAX(id_historial), 0) FROM public.pedidos_estados_historial",
                new MapSqlParameterSource(), Long.class);
        long bound = maximum == null ? 0 : maximum;
        var params = new MapSqlParameterSource().addValue("afterId", afterId)
                .addValue("untilId", bound).addValue("limit", limit + 1);
        var rows = jdbc.query(SQL, params, (rs, row) -> new PedidoEstadosBatch.Evento(
                rs.getLong("id_historial"), rs.getLong("pedido_id"), rs.getString("uuid_local"),
                rs.getInt("estado_id"), rs.getString("estado"), rs.getString("fecha_cambio")));
        boolean hasMore = rows.size() > limit;
        var data = java.util.List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        long lastId = data.isEmpty() ? afterId : data.getLast().historialId();
        return new PedidoEstadosBatch(lastId, bound, hasMore, data);
    }
}
