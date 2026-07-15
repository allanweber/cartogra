package io.cartogra.gateway.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProxyRequestLoggingFilterTest {

    @Mock
    private FilterChain filterChain;

    private ProxyRequestLoggingFilter filter;

    private Logger filterLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        filter = new ProxyRequestLoggingFilter();

        filterLogger = (Logger) LoggerFactory.getLogger(ProxyRequestLoggingFilter.class);
        originalLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.DEBUG);

        listAppender = new ListAppender<>();
        listAppender.start();
        filterLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        filterLogger.detachAppender(listAppender);
        filterLogger.setLevel(originalLevel);
    }

    @Test
    void debugLogRedactsSensitiveHeadersButKeepsOthers() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/services");
        request.addHeader("Authorization", "Bearer secret-token-value");
        request.addHeader("Cookie", "jwt=secret-jwt-value");
        request.addHeader("X-Tenant-Id", "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());

        assertThat(listAppender.list).hasSize(1);
        String message = listAppender.list.get(0).getFormattedMessage();

        assertThat(message).doesNotContain("secret-token-value");
        assertThat(message).doesNotContain("secret-jwt-value");
        assertThat(message).contains("Authorization=[REDACTED]");
        assertThat(message).contains("Cookie=[REDACTED]");
        assertThat(message).contains("X-Tenant-Id=abc");
    }
}
