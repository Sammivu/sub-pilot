package co.subpilot.customer.service;

import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.repository.CustomerRepository;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.webhook.dto.NombaWebhookEvent;
import co.subpilot.nomba.dto.TokenizedCardData;
import co.subpilot.subscription.entity.Subscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final NombaPaymentGateway nomba;
    private static final String SAVED_CARDS_CACHE_PREFIX = "customer:saved-cards:";
    private static final Duration SAVED_CARDS_CACHE_TTL = Duration.ofMinutes(60);
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Max pages to scan looking for this customer's email — Nomba's list
     * endpoint is per-ACCOUNT (all customers, all merchants sharing this
     * Nomba account), not filterable by customer server-side, so finding
     * one customer's cards means paginating through the account's full
     * list and matching by email in-code. Capped to avoid an unbounded
     * scan turning a single customer-detail request into dozens of Nomba
     * API calls for a busy merchant.
     */
    private static final int MAX_PAGES_SCANNED = 20;

    /**
     * Best-effort — returns an empty list on any failure rather than
     * letting a Nomba outage take down the customer-detail endpoint
     * entirely. This is enrichment, not the source of truth (the
     * customer's own cardToken/cardLast4/cardBrand columns remain that).
     */
    @SuppressWarnings("unchecked")
    public List<NombaPaymentGateway.TokenizedCard> fetchSavedCards(String customerEmail) {

        String cacheKey = SAVED_CARDS_CACHE_PREFIX + customerEmail.toLowerCase();
        List<NombaPaymentGateway.TokenizedCard> cached = (List<NombaPaymentGateway.TokenizedCard>) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            log.debug("Loaded saved cards from Redis for {}", customerEmail);
            return cached;
        }
        Map<String, NombaPaymentGateway.TokenizedCard> uniqueCards = new LinkedHashMap<>();
        String page = null;
        int pagesScanned = 0;

        try {
            do {
                var result = nomba.listTokenizedCards(page);

                for (var card : result.cards()) {
                    if (customerEmail.equalsIgnoreCase(card.customerEmail())) {
                        uniqueCards.putIfAbsent(card.tokenKey(), card);
                    }
                }

                page = result.nextPage();
                pagesScanned++;
            } while (page != null && pagesScanned < MAX_PAGES_SCANNED);

        } catch (Exception e) {
            log.warn("Failed to fetch saved cards from Nomba for customerEmail={}: {}", customerEmail, e.getMessage());
            return List.of();
        }
        List<NombaPaymentGateway.TokenizedCard> cards = new ArrayList<>(uniqueCards.values());
        redisTemplate.opsForValue().set(cacheKey, cards, SAVED_CARDS_CACHE_TTL);
        return cards;    }


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

    public Page<Customer> search(String merchantId, String q, Pageable pageable) {
        if (q == null || q.isBlank()) {
            return customerRepository.findByMerchantId(merchantId, pageable);
        }
        return customerRepository.search(merchantId, q, pageable);
    }
}