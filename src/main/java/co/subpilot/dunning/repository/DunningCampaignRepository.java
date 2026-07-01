package co.subpilot.dunning.repository;

import co.subpilot.dunning.entity.DunningCampaign;
import co.subpilot.dunning.entity.DunningExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DunningCampaignRepository extends JpaRepository<DunningCampaign, String> {
    Optional<DunningCampaign> findByMerchantIdAndIsDefaultTrue(String merchantId);
    List<DunningCampaign> findByMerchantId(String merchantId);
}