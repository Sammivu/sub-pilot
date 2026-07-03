package co.subpilot.merchant.entity;

import co.subpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "merchants")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Merchant extends BaseEntity {

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "webhook_signing_secret", nullable = false)
    private String webhookSigningSecret;

    /**
     * Per-merchant override of SubPilot's platform take-rate, in basis points
     * (150 = 1.5%). NULL means "use the platform default"
     * (subpilot.fees.default-bps).
     */
    @Column(name = "fee_bps")
    private Integer feeBps;

    /**
     * Per-merchant override of the fixed fee charged per transaction, in
     * minor units (kobo). NULL means "use the platform default"
     * (subpilot.fees.default-fixed-minor).
     */
    @Column(name = "fee_fixed_minor")
    private Long feeFixedMinor;

    /**
     * Payout destination — an external Nigerian bank account, not a Nomba
     * sub-account. Nomba has no self-serve API to create a new independent
     * account for an arbitrary business (their "virtual account" API
     * creates COLLECTION accounts for a merchant's own customers, not
     * settlement accounts for the merchant itself; the only way a merchant
     * gets a real Nomba accountId is signing up with Nomba directly, which
     * isn't realistic to require of every SubPilot merchant). Using
     * POST /v2/transfers/bank instead of the wallet-to-wallet transfer
     * only needs a standard NUBAN + bank code, which any merchant already
     * has — see DisbursementService / NombaGatewayImpl.initiateBankTransfer.
     */
    @Column(name = "payout_bank_account_number")
    private String payoutBankAccountNumber;

    @Column(name = "payout_bank_code")
    private String payoutBankCode;

    @Column(name = "payout_account_name")
    private String payoutAccountName;
}