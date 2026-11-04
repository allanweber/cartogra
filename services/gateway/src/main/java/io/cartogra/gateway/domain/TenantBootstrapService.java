package io.cartogra.gateway.domain;

import io.cartogra.gateway.config.TenantBootstrapProperties;
import io.cartogra.gateway.repository.TenantRepository;
import io.cartogra.gateway.repository.UserRepository;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Creates the bootstrap tenant and its verified admin when missing. Never changes an existing tenant or user,
 * so a password rotation means deleting the admin first, and config can never turn an existing tenant into a
 * public sandbox. Not transactional on purpose: concurrent callers
 * (retry scheduler, other replicas) lose the insert race on a unique key and treat it as already done.
 */
@Service
public class TenantBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrapService.class);

    private final TenantBootstrapProperties props;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TenantBootstrapService(TenantBootstrapProperties props, TenantRepository tenantRepository,
                             UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.props = props;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void ensureExists() {
        if (!props.enabled()) {
            return;
        }
        if (isBlank(props.name()) || isBlank(props.adminEmail()) || isBlank(props.adminPassword())) {
            throw new IllegalStateException("app.bootstrap.tenant.name, admin-email and admin-password are required");
        }
        if (tenantRepository.findByTenantId(props.tenantId()).isEmpty()) {
            try {
                tenantRepository.save(new Tenant(props.tenantId(), props.tenantId(), props.name(), null,
                    props.plan(), null, null, null, props.publicSandbox()));
                log.info("tenant_bootstrap.created tenantId={} plan={}", props.tenantId(), props.plan());
            } catch (DuplicateKeyException _) {
                log.debug("tenant_bootstrap.already_created tenantId={}", props.tenantId());
            }
        }
        if (userRepository.findByTenantAndEmail(props.tenantId(), props.adminEmail()).isPresent()) {
            return;
        }
        if (userRepository.findByEmail(props.adminEmail()).isPresent()) {
            throw new IllegalStateException("bootstrap admin email already belongs to another tenant");
        }
        try {
            userRepository.save(new User(null, props.tenantId(), props.adminEmail(), props.name() + " Admin", "local",
                null, passwordEncoder.encode(props.adminPassword()), true, List.of("ADMIN"),
                null, null, null, null, null, null, null, null));
            log.info("tenant_bootstrap.admin_created tenantId={}", props.tenantId());
        } catch (DuplicateKeyException _) {
            log.debug("tenant_bootstrap.admin_already_created tenantId={}", props.tenantId());
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
