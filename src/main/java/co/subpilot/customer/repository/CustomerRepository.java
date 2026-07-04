package co.subpilot.customer.repository;

import co.subpilot.customer.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, String> {
    Optional<Customer> findByMerchantIdAndEmail(String merchantId, String email);
    Optional<Customer> findByIdAndMerchantId(String id, String merchantId);
    Page<Customer> findByMerchantId(String merchantId, Pageable pageable);

    @Query("SELECT c FROM Customer c WHERE c.merchantId = :merchantId AND (" +
            "LOWER(c.fullName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "LOWER(c.email) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "c.phone LIKE CONCAT('%', :q, '%'))")
    Page<Customer> search(@Param("merchantId") String merchantId,
                          @Param("q") String q,
                          Pageable pageable);

    Optional<Customer> findByEmailAndMerchantId(String email, String merchantId);
}