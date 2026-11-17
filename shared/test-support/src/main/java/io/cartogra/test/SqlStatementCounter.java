package io.cartogra.test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts SQL executions run on the calling thread inside {@link #count}. Executions on other
 * threads (schedulers, Kafka listeners) are ignored, so background work never skews a count.
 * A JDBC batch counts as one execution.
 */
public final class SqlStatementCounter {

    private static final ScopedValue<AtomicInteger> COUNTER = ScopedValue.newInstance();

    private SqlStatementCounter() {
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    public static DataSource wrap(DataSource target) {
        return proxy(DataSource.class, target, (method, result) ->
                result instanceof Connection c ? proxy(Connection.class, c, SqlStatementCounter::wrapStatement) : result);
    }

    public static int count(Action action) throws Exception {
        AtomicInteger counter = new AtomicInteger();
        ScopedValue.where(COUNTER, counter).call(() -> {
            action.run();
            return null;
        });
        return counter.get();
    }

    private static Object wrapStatement(java.lang.reflect.Method method, Object result) {
        return switch (result) {
            case CallableStatement s -> proxy(CallableStatement.class, s, SqlStatementCounter::onExecute);
            case PreparedStatement s -> proxy(PreparedStatement.class, s, SqlStatementCounter::onExecute);
            case Statement s -> proxy(Statement.class, s, SqlStatementCounter::onExecute);
            case null, default -> result;
        };
    }

    private static Object onExecute(java.lang.reflect.Method method, Object result) {
        if (method.getName().startsWith("execute") && COUNTER.isBound()) {
            COUNTER.get().incrementAndGet();
        }
        return result;
    }

    @FunctionalInterface
    private interface AfterCall {
        Object apply(java.lang.reflect.Method method, Object result);
    }

    private static <T> T proxy(Class<T> type, T target, AfterCall after) {
        InvocationHandler handler = (_, method, args) -> {
            try {
                return after.apply(method, method.invoke(target, args));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
