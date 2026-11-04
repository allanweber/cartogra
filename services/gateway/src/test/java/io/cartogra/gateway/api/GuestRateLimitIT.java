package io.cartogra.gateway.api;

import com.jayway.jsonpath.JsonPath;
import io.cartogra.gateway.AbstractGatewayIT;
import io.cartogra.gateway.domain.GuestService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real servlet container, not MockMvc: the bucket a request lands in depends on where the rate
 * limit filter is registered, which MockMvc does not reproduce.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.guest.tenant-id=6f1d2c3b-0000-4000-8000-00000000acdf",
    "app.guest.mints-per-minute=2",
    "app.rate-limit.default-replenish-rate=1",
    "app.rate-limit.default-burst-capacity=3"
})
class GuestRateLimitIT extends AbstractGatewayIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void guestsGetTheirOwnBucketAndMintingIsLimitedPerIp() throws Exception {
        String first = mint().body();
        String second = mint().body();
        assertThat(mint().statusCode()).isEqualTo(429);
        assertThat(mints("issued")).isEqualTo(2);
        assertThat(mints("rate_limited")).isEqualTo(1);

        String firstToken = JsonPath.read(first, "$.data.accessToken");
        String secondToken = JsonPath.read(second, "$.data.accessToken");

        List<Integer> firstStatuses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            firstStatuses.add(read(firstToken));
        }
        assertThat(firstStatuses).contains(429);
        assertThat(read(secondToken)).isNotEqualTo(429);
    }

    private double mints(String outcome) {
        return meterRegistry.counter(GuestService.MINTS_METRIC, "outcome", outcome).count();
    }

    private HttpResponse<String> mint() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/guest"))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private int read(String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/userinfo"))
            .header("Cookie", "jwt=" + token).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
