package co.subpilot.common.entity;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Base entity for all SubPilot domain objects.
 *
 * Uses ULID as primary key — lexicographically sortable by creation time,
 * globally unique, and URL-safe. Much better than UUID for financial records.
 *
 * All subclasses automatically get id, created_at, updated_at.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @Column(name = "id", length = 26, nullable = false, updatable = false)
    private String id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void prePersist() {
        if (this.id == null) {
            this.id = UlidCreator.getMonotonicUlid().toString();
        }
    }
}
