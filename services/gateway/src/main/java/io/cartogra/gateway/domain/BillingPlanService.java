package io.cartogra.gateway.domain;

import io.cartogra.gateway.domain.exception.NotFoundException;
import io.cartogra.gateway.repository.BillingPlanRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BillingPlanService {

    private final BillingPlanRepository repository;

    public BillingPlanService(BillingPlanRepository repository) {
        this.repository = repository;
    }

    public BillingPlan getBySlug(String slug) {
        return repository.findBySlug(slug)
            .orElseThrow(() -> new NotFoundException("Billing plan not found: " + slug));
    }

    public List<BillingPlan> listPlans() {
        return repository.findAll();
    }
}
