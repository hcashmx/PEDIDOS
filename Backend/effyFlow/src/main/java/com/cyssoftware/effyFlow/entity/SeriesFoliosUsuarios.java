package com.cyssoftware.effyFlow.entity;


import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "series_folios_usuarios")
@Data
public class SeriesFoliosUsuarios {

    @Id
    @Column(name = "serie_id")
    private Integer serieId;

    @Column(name = "usuario_id")
    private Integer usuarioId;

    @Column(name = "puede_usar")
    private Boolean puedeUsar;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
