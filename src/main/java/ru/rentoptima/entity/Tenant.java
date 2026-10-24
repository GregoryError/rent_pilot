package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenants")
@Getter @Setter @NoArgsConstructor
public class Tenant extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private Boolean active = true;

    /** Администратор отключил и публичные страницы аккаунта (V48): /b/ — 410, заявок нет. */
    @Column(name = "public_pages_off", nullable = false)
    private Boolean publicPagesOff = false;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
