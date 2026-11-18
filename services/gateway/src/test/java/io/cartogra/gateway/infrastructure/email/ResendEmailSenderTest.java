package io.cartogra.gateway.infrastructure.email;

import io.cartogra.gateway.config.FrontendProperties;
import io.cartogra.gateway.config.ResendConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ResendEmailSenderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> sentBody = new AtomicReference<>();
    private ResendEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> sentBody.set(request.getBody().toString()))
                .andRespond(withSuccess());
        sender = new ResendEmailSender(builder, new ResendConfig("re_key", "noreply@cartogra.dev", false),
                new FrontendProperties("https://app.cartogra.dev"));
    }

    @Test
    void tenantNameCannotInjectHtmlOrJsonFields() throws Exception {
        String hostile = "Acme\\u003ca href=https://evil.example\\u003eClick\\u003c/a\\u003e\", \"subject\": \"pwned";

        sender.sendInvite("victim@example.com", hostile, "tok-123");

        JsonNode body = mapper.readTree(sentBody.get());
        assertThat(body.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactly("from", "to", "subject", "html");
        assertThat(body.get("subject").stringValue()).isEqualTo("You're invited to join Cartogra");
        String html = body.get("html").stringValue();
        assertThat(html).doesNotContain("evil.example\">").doesNotContain("<a href=https");
        assertThat(html).contains("&quot;").contains("Acme\\u003ca");
        assertThat(html).contains("<a href=\"https://app.cartogra.dev/accept-invite?token=tok-123\">");
    }

    @Test
    void tenantNameWithRealAngleBracketsIsEscaped() throws Exception {
        sender.sendInvite("victim@example.com", "<a href='https://evil.example'>x</a>", "tok");

        String html = mapper.readTree(sentBody.get()).get("html").stringValue();
        assertThat(html).doesNotContain("<a href='https://evil.example'>")
                .contains("&lt;a href=&#39;https://evil.example&#39;&gt;");
    }

    @Test
    void recipientWithQuotesStaysOneAddress() throws Exception {
        sender.sendVerification("a\"@example.com", "123456");

        JsonNode body = mapper.readTree(sentBody.get());
        assertThat(body.get("to")).hasSize(1);
        assertThat(body.get("to").get(0).stringValue()).isEqualTo("a\"@example.com");
        assertThat(body.get("html").stringValue()).contains("<strong>123456</strong>");
    }
}
