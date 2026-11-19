package io.cartogra.web.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TenantAwareDataSourceTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);

    @BeforeEach
    void setUp() throws SQLException {
        when(connection.prepareStatement(TenantAwareDataSource.SET_SCOPE_SQL)).thenReturn(statement);
        when(connection.getAutoCommit()).thenReturn(true);
    }

    @Test
    void writesTheTenantOfTheScope() throws SQLException {
        Connection result = TenantContext.callAs(TENANT, () -> TenantAwareDataSource.applyScope(connection));

        assertThat(result).isSameAs(connection);
        verify(statement).setString(1, TENANT.toString());
        verify(statement).setString(2, "off");
        verify(statement).execute();
        verify(connection, never()).commit();
    }

    @Test
    void writesTheBypassInSystemScope() throws SQLException {
        TenantDb.asSystem(() -> TenantAwareDataSource.applyScope(connection));

        verify(statement).setString(1, "");
        verify(statement).setString(2, "on");
    }

    @Test
    void writesAnEmptyTenantWithoutScope() throws SQLException {
        TenantAwareDataSource.applyScope(connection);

        verify(statement).setString(1, "");
        verify(statement).setString(2, "off");
    }

    @Test
    void commitsTheSettingsWhenAutoCommitIsOff() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);

        TenantAwareDataSource.applyScope(connection);

        verify(connection).commit();
    }

    @Test
    void closesTheConnectionWhenTheSettingsFail() throws SQLException {
        when(statement.execute()).thenThrow(new SQLException("boom"));

        assertThatThrownBy(() -> TenantAwareDataSource.applyScope(connection)).isInstanceOf(SQLException.class);
        verify(connection).close();
    }
}
