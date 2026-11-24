package io.cartogra.contract.api.dto;

import io.cartogra.contract.domain.CheckResult;

import java.util.List;

public record ContractCheckResponse(
        boolean isBreaking,
        int baselineVersion,
        List<VersionDiffResponse.RuleHit> changes,
        List<ConsumerResponse> affectedConsumers
) {
    public static ContractCheckResponse from(CheckResult r) {
        return new ContractCheckResponse(r.isBreaking(), r.baselineVersion(),
                r.changes().stream().map(VersionDiffResponse.RuleHit::from).toList(),
                r.affectedConsumers().stream().map(ConsumerResponse::from).toList());
    }
}
