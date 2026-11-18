package io.cartogra.test;

import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Creates the database roles of production in a test container. */
public final class PostgresRoles {
    private PostgresRoles() {}

    public static final String APP_USER = "cartogra_app";
    public static final String APP_PASSWORD = "cartogra_app";
    public static final String OWNER_USER = "cartogra_owner";
    public static final String OWNER_PASSWORD = "cartogra_owner";

    private static final String ROLES_SCRIPT = "bootstrap-roles.sql";
    private static final int ROLE_SCRIPT_ATTEMPTS = 3;

    /**
     * Runs the production role script. It is idempotent, and it also gives schemas that an earlier run created
     * as the superuser to the owner role. Test JVMs of other modules can run it at the same time on the reused
     * container, so a failed attempt is tried again.
     */
    public static void create(PostgreSQLContainer container) {
        container.copyFileToContainer(MountableFile.forClasspathResource(ROLES_SCRIPT), "/tmp/" + ROLES_SCRIPT);
        String lastError = "";
        for (int attempt = 1; attempt <= ROLE_SCRIPT_ATTEMPTS; attempt++) {
            try {
                Container.ExecResult result = container.execInContainer("psql", "-v", "ON_ERROR_STOP=1",
                        "-U", container.getUsername(), "-d", container.getDatabaseName(),
                        "-v", "owner_password=" + OWNER_PASSWORD, "-v", "app_password=" + APP_PASSWORD,
                        "-f", "/tmp/" + ROLES_SCRIPT);
                if (result.getExitCode() == 0) {
                    return;
                }
                lastError = result.getStderr();
            } catch (java.io.IOException e) {
                lastError = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while the test roles were created", e);
            }
        }
        throw new IllegalStateException("Cannot create the test database roles: " + lastError);
    }
}
