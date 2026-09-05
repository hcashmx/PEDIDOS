package com.cyssoftware.effyFlow.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "series_folios")
@Data
public class SeriesFolios {

    @Id
    @Column(name = "id_serie")
    private Integer idSerie;

    @Column(name = "codigo_serie")
    private String codigoSerie;

    @Column(name = "descripcion")
    private String descripcion;

    @Column(name = "tipo_movimiento_id")
    private Integer tipoMovimientoId;

    @Column(name = "formato_impresion")
    private String formatoImpresion;

    @Column(name = "serie_devolucion_id")
    private Integer serieDevolucionId;

    @Column(name = "activa")
    private Boolean activa;

    @Column(name = "es_consecutiva")
    private Boolean esConsecutiva;

    @Column(name = "siguiente_folio", nullable = false)
    private Integer siguienteFolio;

    @Column(name = "cfdi")
    private Boolean cfdi;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
