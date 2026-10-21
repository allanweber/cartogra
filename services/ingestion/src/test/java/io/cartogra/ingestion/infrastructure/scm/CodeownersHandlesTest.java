package io.cartogra.ingestion.infrastructure.scm;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CodeownersHandlesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "@acme/payments|payments",
            "@payments|payments",
            "[Acme]\\Payments|Payments",
            "Acme\\Payments|Payments",
            "[Platform Team]|Platform Team",
            "[Acme Corp]\\Platform Team|Platform Team",
            "  @acme/ledger  |ledger"
    })
    void reducesHandleToTeamName(String handle, String expected) {
        assertThat(CodeownersHandles.teamName(handle)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@acme.com", "@", "@acme/", "[]", "   "})
    void rejectsNonTeamHandles(String handle) {
        assertThat(CodeownersHandles.teamName(handle)).isEmpty();
    }
}
