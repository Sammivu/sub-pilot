package co.subpilot.proration.repository;

import co.subpilot.proration.entity.ProrationRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProrationRecordRepository extends JpaRepository<ProrationRecord, String> {

    List<ProrationRecord> findBySubscriptionIdOrderByAppliedAtDesc(String subscriptionId);

    Page<ProrationRecord> findByMerchantIdOrderByAppliedAtDesc(String merchantId, Pageable pageable);
}