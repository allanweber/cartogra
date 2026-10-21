package io.cartogra.registry.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TeamTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Payments|payments",
            "payments|payments",
            "Platform Team|platform-team",
            "platform-team|platform-team",
            "Risk & Compliance|risk-compliance",
            "Team_42|team-42"
    })
    void slugMatchesAcmeTeamSlug(String name, String expected) {
        assertThat(Team.slug(name)).isEqualTo(expected);
    }
}
