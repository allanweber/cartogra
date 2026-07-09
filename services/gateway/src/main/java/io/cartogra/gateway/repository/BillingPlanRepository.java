package io.cartogra.gateway.repository;

import io.cartogra.gateway.domain.BillingPlan;

import java.util.List;
import java.util.Optional;

public interface BillingPlanRepository {
    Optional<BillingPlan> findBySlug(String slug);
    List<BillingPlan> findAll();
}
