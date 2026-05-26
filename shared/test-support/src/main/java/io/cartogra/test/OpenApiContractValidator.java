package io.cartogra.test;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public final class OpenApiContractValidator {

    private final OpenApiInteractionValidator validator;

    public OpenApiContractValidator(String specClasspathResource) {
        var url = getClass().getClassLoader().getResource(specClasspathResource);
        if (url == null) {
            throw new IllegalStateException("OpenAPI spec not found on classpath: " + specClasspathResource);
        }
        this.validator = OpenApiInteractionValidator
                .createForSpecificationUrl(url.toString())
                .build();
    }

    /**
     * Validates the response body and headers against the OpenAPI spec for the given
     * path/method/status. Fails the test immediately with a descriptive message if any
     * spec violations are found.
     */
    public void assertResponse(String method, String path, int status,
                               String body, Map<String, String> headers) {
        var builder = SimpleResponse.Builder.status(status).withBody(body);
        headers.forEach(builder::withHeader);
        var response = builder.build();

        var report = validator.validateResponse(path, Request.Method.valueOf(method.toUpperCase()), response);
        assertThat(report.hasErrors())
                .as("OpenAPI contract violations for %s %s → %d:\n%s", method, path, status, report)
                .isFalse();
    }
}
