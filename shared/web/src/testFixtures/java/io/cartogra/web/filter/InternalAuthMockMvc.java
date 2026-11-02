package io.cartogra.web.filter;

import io.cartogra.common.identity.Actor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

/**
 * Gives a standalone MockMvc the services' production auth path: {@code X-User-Id} / {@code X-User-Roles}
 * headers become an {@link Actor} principal, anonymous requests get {@link Actor#ANONYMOUS}, and the
 * context is cleared after each request.
 */
public final class InternalAuthMockMvc {

    private InternalAuthMockMvc() {}

    public static StandaloneMockMvcBuilder withInternalAuth(StandaloneMockMvcBuilder builder) {
        return builder
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(
                        new SecurityContextHolderFilter(new RequestAttributeSecurityContextRepository()),
                        new InternalAuthFilter(),
                        new AnonymousAuthenticationFilter("test", Actor.ANONYMOUS,
                                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    }
}
