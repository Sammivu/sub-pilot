package co.subpilot.auth.entity;

import co.subpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "role", nullable = false)
    @Builder.Default
    private String role = "owner";

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;
}