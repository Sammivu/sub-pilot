package co.subpilot.customer.dto;

import co.subpilot.customer.entity.Customer;
import co.subpilot.nomba.NombaPaymentGateway;

import java.util.List;

public class CustomerDtos {

    public record SavedCard(String tokenKey, String cardType, String cardPan, String tokenExpirationDate) {
        public static SavedCard from(NombaPaymentGateway.TokenizedCard c) {
            return new SavedCard(c.tokenKey(), c.cardType(), c.cardPan(), c.tokenExpirationDate());
        }
    }

    /**
     * Wraps the persisted Customer plus live-fetched saved cards from
     * Nomba — savedCards reflects what Nomba's account actually has on
     * file right now (matched by email), independent of whatever this
     * customer's locally-cached cardToken/cardLast4/cardBrand columns say.
     * The two can legitimately differ if a customer has cards tokenized
     * outside SubPilot's own flow, or if our local cache is stale.
     */
    public record CustomerDetailResponse(
            String id, String merchantId, String fullName, String email, String phone,
            String nombaCustomerId, String cardToken, String cardLast4, String cardExpiry, String cardBrand,
            String createdAt,
            List<SavedCard> savedCards
    ) {
        public static CustomerDetailResponse from(Customer c, List<SavedCard> savedCards) {
            return new CustomerDetailResponse(
                    c.getId(), c.getMerchantId(), c.getFullName(), c.getEmail(), c.getPhone(),
                    c.getNombaCustomerId(), c.getCardToken(), c.getCardLast4(), c.getCardExpiry(), c.getCardBrand(),
                    c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                    savedCards
            );
        }
    }
}
