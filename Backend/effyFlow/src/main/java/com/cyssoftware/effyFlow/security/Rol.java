package com.cyssoftware.effyFlow.security;


import java.util.Set;

public enum Rol {

    AGENTE(Set.of(
            Permiso.VER_MODULO_PEDIDOS,
            Permiso.CREAR_PEDIDO,
            Permiso.SINCRONIZAR_DATOS
    )),

    COBRANZA(Set.of(
            Permiso.VER_MODULO_PEDIDOS,
            Permiso.MODIFICAR_PLAZO_CLIENTE
    )),

    ADMIN(Set.of(
            Permiso.VER_MODULO_PEDIDOS,
            Permiso.CREAR_PEDIDO,
            Permiso.SINCRONIZAR_DATOS,
            Permiso.VER_PEDIDOS_OTROS,
            Permiso.MODIFICAR_PLAZO_CLIENTE,
            Permiso.EXPORTAR_PEDIDOS
    ));

    private final Set<Permiso> permisos;

    Rol(Set<Permiso> permisos) {
        this.permisos = permisos;
    }

    public Set<Permiso> getPermisos() {
        return permisos;
    }
}
