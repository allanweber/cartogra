package io.cartogra.test;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementCounterTest {

    private final DataSource dataSource = SqlStatementCounter.wrap(fake(DataSource.class));

    @Test
    void countsEveryExecutionOnTheCallingThread() throws Exception {
        int count = SqlStatementCounter.count(() -> {
            try (Connection c = dataSource.getConnection()) {
                c.prepareStatement("SELECT 1").executeQuery();
                c.prepareStatement("UPDATE t SET x = 1").executeUpdate();
                c.createStatement().execute("SELECT 2");
                c.prepareCall("CALL p()").execute();
            }
        });

        assertThat(count).isEqualTo(4);
    }

    @Test
    void aBatchCountsAsOneExecution() throws Exception {
        int count = SqlStatementCounter.count(() -> {
            try (Connection c = dataSource.getConnection()) {
                PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)");
                ps.addBatch();
                ps.addBatch();
                ps.executeBatch();
            }
        });

        assertThat(count).isEqualTo(1);
    }

    @Test
    void preparingWithoutExecutingCountsNothing() throws Exception {
        int count = SqlStatementCounter.count(() -> {
            try (Connection c = dataSource.getConnection()) {
                c.prepareStatement("SELECT 1");
            }
        });

        assertThat(count).isZero();
    }

    @Test
    void ignoresExecutionsOutsideCountAndOnOtherThreads() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.prepareStatement("SELECT 1").executeQuery();
        }

        int count = SqlStatementCounter.count(() -> {
            Thread other = Thread.ofVirtual().start(() -> {
                try (Connection c = dataSource.getConnection()) {
                    c.prepareStatement("SELECT 1").executeQuery();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            other.join();
        });

        assertThat(count).isZero();
    }

    @Test
    void countsAreIndependentPerCall() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        SqlStatementCounter.Action twoQueries = () -> {
            runs.incrementAndGet();
            try (Connection c = dataSource.getConnection()) {
                c.prepareStatement("SELECT 1").executeQuery();
                c.prepareStatement("SELECT 2").executeQuery();
            }
        };

        assertThat(SqlStatementCounter.count(twoQueries)).isEqualTo(2);
        assertThat(SqlStatementCounter.count(twoQueries)).isEqualTo(2);
        assertThat(runs).hasValue(2);
    }

    /** A JDBC object whose methods return further fakes for JDBC interfaces and defaults otherwise. */
    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (_, method, _) -> {
            Class<?> r = method.getReturnType();
            if (r == Connection.class || r == PreparedStatement.class || r == Statement.class
                    || r == CallableStatement.class || r == java.sql.ResultSet.class) {
                return fake(r);
            }
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class) {
                return 0;
            }
            if (r == int[].class) {
                return new int[0];
            }
            return null;
        });
    }
}
