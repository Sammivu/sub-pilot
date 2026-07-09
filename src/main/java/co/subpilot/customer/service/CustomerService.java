package co.subpilot.customer.service;

import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.repository.CustomerRepository;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.webhook.dto.NombaWebhookEvent;
import co.subpilot.nomba.dto.TokenizedCardData;
import co.subpilot.subscription.entity.Subscription;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final NombaPaymentGateway nomba;
    private static final String SAVED_CARDS_CACHE_PREFIX = "customer:saved-cards:";
    private static final Duration SAVED_CARDS_CACHE_TTL = Duration.ofMinutes(60);
    private final RedisTemplate<String, Object> redisTemplate;

    private final Cache<String, List<NombaPaymentGateway.TokenizedCard>> savedCardsCache =
            Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(120))
                    .maximumSize(10_000)
                    .build();

    /**
     * Max pages to scan looking for this customer's email — Nomba's list
     * endpoint is per-ACCOUNT (all customers, all merchants sharing this
     * Nomba account), not filterable by customer server-side, so finding
     * one customer's cards means paginating through the account's full
     * list and matching by email in-code. Capped to avoid an unbounded
     * scan turning a single customer-detail request into dozens of Nomba
     * API calls for a busy merchant.
     */
    private static final int MAX_PAGES_SCANNED = 5;

    /**
     * Best-effort — returns an empty list on any failure rather than
     * letting a Nomba outage take down the customer-detail endpoint
     * entirely. This is enrichment, not the source of truth (the
     * customer's own cardToken/cardLast4/cardBrand columns remain that).
     */
    @SuppressWarnings("unchecked")
    public List<NombaPaymentGateway.TokenizedCard> fetchSavedCards(String customerEmail) {
        String cacheKey = customerEmail.toLowerCase();

        List<NombaPaymentGateway.TokenizedCard> cached = savedCardsCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("Loaded saved cards from in-memory cache for {}", customerEmail);
            return cached;
        }
        Map<String, NombaPaymentGateway.TokenizedCard> uniqueCards = new LinkedHashMap<>();
        Set<String> visitedPages = new HashSet<>();
        String page = null;
        int pagesScanned = 0;

        try {
            do {
                // Prevent pagination loops (e.g. 2 -> 0 -> 2 -> 0)
                String currentPage = (page == null) ? "FIRST" : page;
                if (!visitedPages.add(currentPage)) {
                    log.warn("Detected pagination loop while fetching saved cards. page={}", currentPage);
                    break;
                }
                log.debug("Fetching tokenized cards page={}", currentPage);
                var result = nomba.listTokenizedCards(page);

                for (var card : result.cards()) {
                    if (customerEmail.equalsIgnoreCase(card.customerEmail())) {
                        uniqueCards.putIfAbsent(card.tokenKey(), card);
                    }
                }
                String nextPage = result.nextPage();
                log.debug("Current page={}, nextPage={}", currentPage, nextPage);
                page = nextPage;
                pagesScanned++;
            } while (page != null && pagesScanned < MAX_PAGES_SCANNED);

        } catch (Exception e) {
            log.warn("Failed to fetch saved cards from Nomba for customerEmail={}: {}",
                    customerEmail, e.getMessage(), e);
            return List.of();
        }
        List<NombaPaymentGateway.TokenizedCard> cards = new ArrayList<>(uniqueCards.values());
        savedCardsCache.put(cacheKey, cards);

        return cards;
    }


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