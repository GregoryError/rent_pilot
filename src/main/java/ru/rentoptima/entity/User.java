package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class User extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Role role = Role.OWNER;

    @Column(name = "tg_chat_id")
    private String tgChatId;

    @Column(unique = true)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private Boolean emailVerified = true;

    @Column(name = "email_verification_token")
    private String emailVerificationToken;

    @Column(name = "email_verification_expires_at")
    private java.time.LocalDateTime emailVerificationExpiresAt;

    @Column(name = "password_reset_token")
    private String passwordResetToken;

    @Column(name = "password_reset_expires_at")
    private java.time.LocalDateTime passwordResetExpiresAt;

    @Column(name = "agreed_to_pd_at")
    private java.time.LocalDateTime agreedToPdAt;

    @Column(nullable = false)
    private Boolean active = true;

    public enum Role {
        ADMIN, OWNER
    }
}
