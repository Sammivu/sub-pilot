package co.subpilot.internal.fee.repository;

import co.subpilot.internal.fee.entity.PlatformFeeDefault;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformFeeDefaultRepository extends JpaRepository<PlatformFeeDefault, String> {
}