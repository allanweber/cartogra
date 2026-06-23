package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.repository.UserRepository;

import io.cartogra.gateway.domain.User;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> findById(UUID id) {
        String sql = "SELECT * FROM users WHERE id = :id AND deleted_at IS NULL";
        var params = new MapSqlParameterSource("id", id);
        return jdbc.query(sql, params, JdbcUserRepository::mapRow).stream().findFirst();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        String sql = "SELECT * FROM users WHERE email = :email AND deleted_at IS NULL LIMIT 1";
        var params = new MapSqlParameterSource("email", email);
        return jdbc.query(sql, params, JdbcUserRepository::mapRow).stream().findFirst();
    }

    @Override
    public List<User> findAllByEmail(String email) {
        String sql = "SELECT * FROM users WHERE email = :email AND deleted_at IS NULL";
        var params = new MapSqlParameterSource("email", email);
        return jdbc.query(sql, params, JdbcUserRepository::mapRow);
    }

    @Override
    public Optional<User> findByTenantAndEmail(UUID tenantId, String email) {
        String sql = """
            SELECT * FROM users
            WHERE tenant_id = :tenantId AND email = :email AND deleted_at IS NULL
            """;
        var params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("email", email);
        return jdbc.query(sql, params, JdbcUserRepository::mapRow).stream().findFirst();
    }

    @Override
    public Optional<User> findByVerificationToken(String token) {
        String sql = """
            SELECT * FROM users
            WHERE email_verification_token = :token AND deleted_at IS NULL
            """;
        var params = new MapSqlParameterSource("token", token);
        return jdbc.query(sql, params, JdbcUserRepository::mapRow).stream().findFirst();
    }

    @Override
    public User save(User user) {
        if (user.id() == null) {
            return insert(user);
        }
        return update(user);
    }

    @Override
    public Optional<User> findByPasswordResetToken(String token) {
        String sql = """
            SELECT * FROM users
            WHERE password_reset_token = :token AND deleted_at IS NULL
            """;
        var params = new MapSqlParameterSource("token", token);
        return jdbc.query(sql, params, JdbcUserRepository::mapRow).stream().findFirst();
    }

    private User insert(User user) {
        UUID id = UUID.randomUUID();
        String sql = """
            INSERT INTO users (id, tenant_id, email, name, auth_provider, auth_subject, password_hash,
                email_verified, email_verification_token, email_verification_token_exp,
                password_reset_token, password_reset_token_exp,
                roles, created_at, updated_at)
            VALUES (:id, :tenantId, :email, :name, :authProvider, :authSubject, :passwordHash,
                :emailVerified, :emailVerificationToken, :emailVerificationTokenExp,
                :passwordResetToken, :passwordResetTokenExp,
                :roles::text[], :createdAt, :updatedAt)
            """;
        Instant now = Instant.now();
        var params = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", user.tenantId())
            .addValue("email", user.email())
            .addValue("name", user.name())
            .addValue("authProvider", user.authProvider())
            .addValue("authSubject", user.authSubject())
            .addValue("passwordHash", user.passwordHash())
            .addValue("emailVerified", user.emailVerified())
            .addValue("emailVerificationToken", user.emailVerificationToken())
            .addValue("emailVerificationTokenExp", user.emailVerificationTokenExp() != null
                ? Timestamp.from(user.emailVerificationTokenExp()) : null)
            .addValue("passwordResetToken", user.passwordResetToken())
            .addValue("passwordResetTokenExp", user.passwordResetTokenExp() != null
                ? Timestamp.from(user.passwordResetTokenExp()) : null)
            .addValue("roles", "{" + String.join(",", user.roles()) + "}")
            .addValue("createdAt", Timestamp.from(now))
            .addValue("updatedAt", Timestamp.from(now));
        jdbc.update(sql, params);
        return new User(id, user.tenantId(), user.email(), user.name(), user.authProvider(),
            user.authSubject(), user.passwordHash(), user.emailVerified(),
            user.roles(), user.emailVerificationToken(), user.emailVerificationTokenExp(),
            user.passwordResetToken(), user.passwordResetTokenExp(),
            now, now, null);
    }

    private User update(User user) {
        String sql = """
            UPDATE users SET email = :email, name = :name, auth_provider = :authProvider, auth_subject = :authSubject,
                password_hash = :passwordHash, email_verified = :emailVerified,
                email_verification_token = :emailVerificationToken,
                email_verification_token_exp = :emailVerificationTokenExp,
                password_reset_token = :passwordResetToken,
                password_reset_token_exp = :passwordResetTokenExp,
                roles = :roles::text[], updated_at = now()
            WHERE id = :id AND deleted_at IS NULL
            """;
        var params = new MapSqlParameterSource()
            .addValue("id", user.id())
            .addValue("email", user.email())
            .addValue("name", user.name())
            .addValue("authProvider", user.authProvider())
            .addValue("authSubject", user.authSubject())
            .addValue("passwordHash", user.passwordHash())
            .addValue("emailVerified", user.emailVerified())
            .addValue("emailVerificationToken", user.emailVerificationToken())
            .addValue("emailVerificationTokenExp", user.emailVerificationTokenExp() != null
                ? Timestamp.from(user.emailVerificationTokenExp()) : null)
            .addValue("passwordResetToken", user.passwordResetToken())
            .addValue("passwordResetTokenExp", user.passwordResetTokenExp() != null
                ? Timestamp.from(user.passwordResetTokenExp()) : null)
            .addValue("roles", "{" + String.join(",", user.roles()) + "}");
        jdbc.update(sql, params);
        return user;
    }

    private static User mapRow(ResultSet rs, int rowNum) throws SQLException {
        String[] rolesArr = (String[]) rs.getArray("roles").getArray();
        List<String> roles = rolesArr != null ? Arrays.asList(rolesArr) : List.of();
        return new User(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            rs.getString("email"),
            rs.getString("name"),
            rs.getString("auth_provider"),
            rs.getString("auth_subject"),
            rs.getString("password_hash"),
            rs.getBoolean("email_verified"),
            roles,
            rs.getString("email_verification_token"),
            toInstant(rs.getTimestamp("email_verification_token_exp")),
            rs.getString("password_reset_token"),
            toInstant(rs.getTimestamp("password_reset_token_exp")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("deleted_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
