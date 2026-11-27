package io.cartogra.contract.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.common.identity.Actor;
import io.cartogra.contract.api.dto.NotificationAttemptResponse;
import io.cartogra.contract.api.dto.NotificationRuleRequest;
import io.cartogra.contract.api.dto.NotificationRuleResponse;
import io.cartogra.contract.domain.NotificationRuleService;
import io.cartogra.web.api.TracedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class NotificationRuleController {

    private final NotificationRuleService ruleService;

    public NotificationRuleController(NotificationRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping({"/notification-rules", "/notification-rules/"})
    public ResponseEntity<ApiResponse<NotificationRuleResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @Valid @RequestBody NotificationRuleRequest request) {
        return TracedResponse.created(NotificationRuleResponse.from(ruleService.create(tenantId, actor, request)));
    }

    @GetMapping({"/notification-rules", "/notification-rules/"})
    public ResponseEntity<ApiResponse<PageResult<NotificationRuleResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return TracedResponse.ok(ruleService.list(tenantId, limit, offset).map(NotificationRuleResponse::from));
    }

    @GetMapping("/notification-rules/{id}")
    public ResponseEntity<ApiResponse<NotificationRuleResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        return TracedResponse.ok(NotificationRuleResponse.from(ruleService.get(tenantId, id)));
    }

    @PutMapping("/notification-rules/{id}")
    public ResponseEntity<ApiResponse<NotificationRuleResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody NotificationRuleRequest request) {
        return TracedResponse.ok(NotificationRuleResponse.from(ruleService.update(tenantId, actor, id, request)));
    }

    @PatchMapping("/notification-rules/{id}")
    public ResponseEntity<ApiResponse<NotificationRuleResponse>> patch(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody NotificationRuleRequest request) {
        return TracedResponse.ok(NotificationRuleResponse.from(
                ruleService.patchEnabled(tenantId, actor, id, request)));
    }

    @PostMapping({"/notification-rules/{id}/enable", "/notification-rules/{id}/enable/"})
    public ResponseEntity<ApiResponse<NotificationRuleResponse>> enable(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id) {
        return TracedResponse.ok(NotificationRuleResponse.from(ruleService.setEnabled(tenantId, actor, id, true)));
    }

    @PostMapping({"/notification-rules/{id}/disable", "/notification-rules/{id}/disable/"})
    public ResponseEntity<ApiResponse<NotificationRuleResponse>> disable(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id) {
        return TracedResponse.ok(NotificationRuleResponse.from(ruleService.setEnabled(tenantId, actor, id, false)));
    }

    @PostMapping({"/notification-rules/{id}/test", "/notification-rules/{id}/test/"})
    public ResponseEntity<ApiResponse<List<NotificationAttemptResponse>>> test(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id) {
        return TracedResponse.ok(ruleService.test(tenantId, actor, id).stream()
                .map(NotificationAttemptResponse::from).toList());
    }

    @GetMapping("/notification-rules/{id}/attempts")
    public ResponseEntity<ApiResponse<PageResult<NotificationAttemptResponse>>> listAttempts(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id) {
        return TracedResponse.ok(ruleService.listAttempts(tenantId, actor, id)
                .map(NotificationAttemptResponse::from));
    }

    @DeleteMapping("/notification-rules/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id) {
        ruleService.delete(tenantId, actor, id);
        return TracedResponse.noContent();
    }
}
