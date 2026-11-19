package io.cartogra.web.tenant;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The Hikari pool of each service (set {@code spring.datasource.type} to this class). It writes the
 * {@link TenantContext} scope to each connection at checkout, as session settings that the
 * {@code tenant_isolation} policies read. Each checkout writes both settings, so a value from an earlier
 * checkout of a pooled connection never stays. No tenant and no system scope gives an empty tenant: the
 * policies then match no rows (fail-closed). It is a Hikari subclass, not a wrapper, so that Spring Boot can
 * still derive the Flyway connection from it.
 */
public class TenantAwareDataSource extends HikariDataSource {

    static final String SET_SCOPE_SQL =
            "SELECT set_config('app.current_tenant_id', ?, false), set_config('app.rls_bypass', ?, false)";

    @Override
    public Connection getConnection() throws SQLException {
        return applyScope(super.getConnection());
    }

    static Connection applyScope(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_SCOPE_SQL)) {
            statement.setString(1, TenantContext.current().map(UUID::toString).orElse(""));
            statement.setString(2, TenantContext.isSystem() ? "on" : "off");
            statement.execute();
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }
}
