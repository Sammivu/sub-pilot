package co.subpilot.dunning.repository;

import co.subpilot.dunning.entity.DunningStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DunningStepRepository extends JpaRepository<DunningStep, String> {

    List<DunningStep> findByCampaignIdOrderByStepNumberAsc(String campaignId);

    Optional<DunningStep> findByIdAndCampaignId(String id, String campaignId);
}