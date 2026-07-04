package co.subpilot.internal.admin.entity;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A SubPilot platform staff account — completely separate from Merchant's
 * User (which is always scoped to exactly one merchant tenant). See
 * V17__create_internal_admin_dashboard.sql for how the first row gets
 * created (deliberately no API path does this in V1).
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "internal_admins")
public class InternalAdmin {

    @Id
    @Column(name = "id", length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** super_admin | ops_admin — see InternalAdminRole. */
    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) id = UlidCreator.getMonotonicUlid().toString();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}