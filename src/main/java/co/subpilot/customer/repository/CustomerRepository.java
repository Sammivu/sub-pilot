package co.subpilot.customer.repository;

import co.subpilot.customer.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, String> {
    Optional<Customer> findByMerchantIdAndEmail(String merchantId, String email);
    Optional<Customer> findByIdAndMerchantId(String id, String merchantId);
    Page<Customer> findByMerchantId(String merchantId, Pageable pageable);

    Optional<Customer> findByEmailAndMerchantId(String email, String merchantId);
}