package co.subpilot.customer.entity;

import co.subpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "customers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Customer extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "nomba_customer_id")
    private String nombaCustomerId;

    @Column(name = "card_token")
    private String cardToken; // tokenised card reference — never raw card data

    @Column(name = "card_last4")
    private String cardLast4;

    @Column(name = "card_expiry")
    private String cardExpiry;

    @Column(name = "card_brand")
    private String cardBrand;
}