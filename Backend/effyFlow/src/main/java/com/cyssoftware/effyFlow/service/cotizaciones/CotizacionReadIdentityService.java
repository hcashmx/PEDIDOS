package com.cyssoftware.effyFlow.service.cotizaciones;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Resolves read authority from the authenticated Sync principal; caller supplied agent IDs are never used. */
@Service
@RequiredArgsConstructor
public class CotizacionReadIdentityService {
    private final NamedParameterJdbcTemplate jdbc;

    public Actor requireReadActor(long userId) {
        String email = jdbc.query("SELECT email FROM public.users WHERE id=:id",
                new MapSqlParameterSource("id", userId), rs -> rs.next() ? rs.getString(1) : null);
        Actor actor = requireReadActor(email);
        if (actor.usuarioErpId() != userId) forbidden();
        return actor;
    }

    public Actor requireReadActor(String email) {
        if (email == null || email.isBlank()) forbidden();
        List<Actor> actors = jdbc.query("""
                SELECT u.id AS usuario_id, a.id_agente AS agente_id
                FROM public.users u
                JOIN public.agentes a ON a.id_usuario=u.id
                WHERE lower(u.email)=lower(:email)
                  AND u.active=true AND COALESCE(upper(u.status),'') <> 'INACTIVO'
                  AND upper(u.role) IN ('AGENTE','VENDEDOR')
                  AND EXISTS (
                    SELECT 1 FROM public.roles allowed_role
                    WHERE lower(allowed_role.guard_name)='web'
                      AND upper(allowed_role.name) IN ('AGENTE','VENDEDOR')
                      AND allowed_role.id IN (
                        SELECT mhr.role_id FROM public.model_has_roles mhr
                        WHERE mhr.model_id=u.id
                          AND (mhr.model_type='App\\Models\\User' OR mhr.model_type LIKE '%User')
                        UNION ALL
                        SELECT u.id_rol WHERE NOT EXISTS (
                          SELECT 1 FROM public.model_has_roles mhr
                          WHERE mhr.model_id=u.id
                            AND (mhr.model_type='App\\Models\\User' OR mhr.model_type LIKE '%User')
                        )
                      )
                  )
                  AND (
                    EXISTS (
                      SELECT 1 FROM public.permissions p
                      WHERE lower(p.guard_name)='web' AND p.name='cotizaciones.ver'
                        AND EXISTS (
                          SELECT 1 FROM public.role_has_permissions rhp
                          JOIN public.roles r ON r.id=rhp.role_id
                          WHERE rhp.permission_id=p.id AND lower(r.guard_name)='web'
                            AND r.id IN (
                              SELECT mhr.role_id FROM public.model_has_roles mhr
                              WHERE mhr.model_id=u.id
                                AND (mhr.model_type='App\\Models\\User' OR mhr.model_type LIKE '%User')
                              UNION ALL
                              SELECT u.id_rol WHERE NOT EXISTS (
                                SELECT 1 FROM public.model_has_roles mhr
                                WHERE mhr.model_id=u.id
                                  AND (mhr.model_type='App\\Models\\User' OR mhr.model_type LIKE '%User')
                              )
                            )
                        )
                    )
                    OR EXISTS (
                      SELECT 1 FROM public.model_has_permissions mhp
                      JOIN public.permissions p ON p.id=mhp.permission_id
                      WHERE mhp.model_id=u.id
                        AND (mhp.model_type='App\\Models\\User' OR mhp.model_type LIKE '%User')
                        AND lower(p.guard_name)='web' AND p.name='cotizaciones.ver'
                    )
                  )
                ORDER BY a.id_agente
                """, new MapSqlParameterSource("email", email), (rs, row) ->
                new Actor(rs.getLong("usuario_id"), rs.getInt("agente_id")));
        if (actors.size() != 1) forbidden();
        return actors.getFirst();
    }

    private static void forbidden() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "COTIZACIONES_SIN_PERMISO: el usuario no cuenta con autorización vigente para leer Cotizaciones.");
    }

    public record Actor(long usuarioErpId, int agenteErpId) { }
}
