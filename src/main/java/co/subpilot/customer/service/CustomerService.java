package co.subpilot.customer.service;

import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.repository.CustomerRepository;
import co.subpilot.webhook.dto.NombaWebhookEvent;
import co.subpilot.nomba.dto.TokenizedCardData;
import co.subpilot.subscription.entity.Subscription;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    /**
     * Finds an existing customer by email within the merchant tenant, or
     * creates a new one. Used during hosted checkout (PRD §6.4).
     */
    @Transactional
    public Customer findOrCreate(String merchantId, String fullName, String email, String phone) {
        return customerRepository.findByEmailAndMerchantId(email, merchantId)
                .orElseGet(() -> {
                    Customer c = new Customer();
                    c.setMerchantId(merchantId);
                    c.setFullName(fullName);
                    c.setEmail(email);
                    c.setPhone(phone);
                    return customerRepository.save(c);
                });
    }

    @Transactional
    public Customer storeCardToken(String customerId, String cardToken, String last4, String expiry, String brand) {
        Customer c = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("customer", customerId));
        c.setCardToken(cardToken);
        c.setCardLast4(last4);
        c.setCardExpiry(expiry);
        c.setCardBrand(brand);
        return customerRepository.save(c);
    }

    private void saveCard(Subscription subscription, NombaWebhookEvent event) {

        Customer customer = customerRepository.findById(subscription.getCustomerId()).orElseThrow();

        TokenizedCardData card = event.data().tokenizedCardData();

        if (card == null) {
            return;
        }

        if (!"N/A".equals(card.tokenKey())) {
            customer.setCardToken(card.tokenKey());
        }
        customer.setCardBrand(card.cardType());

        customer.setCardLast4(event.data().order().cardLast4Digits());

        if (!"N/A".equals(card.tokenExpiryMonth()) && !"N/A".equals(card.tokenExpiryYear())) {

            customer.setCardExpiry(card.tokenExpiryMonth() + "/" + card.tokenExpiryYear());
        }
        customerRepository.save(customer);
    }

    public Customer getOwned(String merchantId, String customerId) {
        return customerRepository.findByIdAndMerchantId(customerId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("customer", customerId));
    }

    public Page<Customer> list(String merchantId, Pageable pageable) {
        return customerRepository.findByMerchantId(merchantId, pageable);
    }
}