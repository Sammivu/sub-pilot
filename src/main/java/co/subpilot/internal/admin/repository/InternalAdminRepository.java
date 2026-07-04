package co.subpilot.internal.admin.repository;

import co.subpilot.internal.admin.entity.InternalAdmin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InternalAdminRepository extends JpaRepository<InternalAdmin, String> {
    Optional<InternalAdmin> findByEmail(String email);
}