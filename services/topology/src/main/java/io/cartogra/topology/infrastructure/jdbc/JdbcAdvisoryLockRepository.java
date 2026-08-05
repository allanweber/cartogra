package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.repository.AdvisoryLockRepository;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Repository
public class JdbcAdvisoryLockRepository implements AdvisoryLockRepository {

    // pg_try_advisory_lock/pg_advisory_unlock are session-scoped: acquire and release must run on the
    // same physical connection, so this bypasses the pooled NamedParameterJdbcTemplate and pins one
    // connection per thread between the two calls.
    private static final ThreadLocal<Connection> HELD_CONNECTION = new ThreadLocal<>();

    private final DataSource dataSource;

    public JdbcAdvisoryLockRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean tryAcquireLock(long key) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                boolean acquired = rs.getBoolean(1);
                if (acquired) {
                    HELD_CONNECTION.set(connection);
                } else {
                    DataSourceUtils.releaseConnection(connection, dataSource);
                }
                return acquired;
            }
        } catch (SQLException e) {
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw new DataAccessResourceFailureException("Failed to acquire advisory lock " + key, e);
        }
    }

    @Override
    public void releaseLock(long key) {
        Connection connection = HELD_CONNECTION.get();
        if (connection == null) {
            return;
        }
        HELD_CONNECTION.remove();
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("Failed to release advisory lock " + key, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
