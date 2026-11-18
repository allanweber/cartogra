package io.cartogra.common.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HttpUrlValidatorTest {

    private final HttpUrlValidator validator = new HttpUrlValidator();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "https://docs.example.com/runbook",
            "http://wiki.internal:8080/page?a=1#x",
            "HTTPS://github.com/org/repo"
    })
    void acceptsHttpAndHttpsUrls(String url) {
        assertThat(validator.isValid(url, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "java\tscript:alert(1)",
            "java\nscript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "ftp://example.com/x",
            "//example.com/x",
            "/relative/path",
            "https://",
            "https:///no-host",
            " https://example.com",
            "https://exa mple.com"
    })
    void rejectsOtherSchemesAndMalformedUrls(String url) {
        assertThat(validator.isValid(url, null)).isFalse();
    }
}
