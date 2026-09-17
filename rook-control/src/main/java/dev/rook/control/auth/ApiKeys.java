package dev.rook.control.auth;

import dev.rook.core.hash.Hashes;
import dev.rook.core.id.Ids;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ApiKeys {

    public record Issued(UUID id, String key, String prefix) {}

    public record Summary(
            UUID id,
            UUID installationId,
            String name,
            String prefix,
            Set<Scope> scopes,
            boolean revoked) {}

    private static final Pattern FORMAT =
            Pattern.compile("^rk_([A-Za-z0-9]{10})_([A-Za-z0-9_-]{43,86})$");
    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String DECOY_HASH = Hashes.sha256Hex("no such key");
    private static final int MINIMUM_BOOTSTRAP_LENGTH = 32;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ApiKeys(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Issued issue(UUID installationId, String name, Set<Scope> scopes) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a key needs a name");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("a key needs at least one scope");
        }
        if (installationId == null && !scopes.contains(Scope.ADMIN)) {
            throw new IllegalArgumentException("a key for every installation must be an admin key");
        }
        String prefix = randomPrefix();
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        insert(installationId, name, prefix, Hashes.sha256Hex(secret), scopes);
        UUID id =
                jdbc.sql("select id from api_key where prefix = :prefix")
                        .param("prefix", prefix)
                        .query(UUID.class)
                        .single();
        return new Issued(id, "rk_" + prefix + "_" + secret, prefix);
    }

    public Optional<Principal> bootstrap(String configuredKey) {
        if (configuredKey == null || configuredKey.length() < MINIMUM_BOOTSTRAP_LENGTH) {
            throw new IllegalArgumentException(
                    "the bootstrap key must have at least "
                            + MINIMUM_BOOTSTRAP_LENGTH
                            + " characters");
        }
        String prefix = "boot" + Hashes.sha256Hex(configuredKey).substring(0, 6);
        String hash = Hashes.sha256Hex(configuredKey);
        insert(null, "bootstrap", prefix, hash, EnumSet.of(Scope.ADMIN));
        return jdbc.sql(
                        "select id, installation_id, name, scopes from api_key"
                                + " where prefix = :prefix and revoked_at is null")
                .param("prefix", prefix)
                .query((rs, row) -> principal(rs))
                .optional();
    }

    public Optional<Principal> authenticate(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(presented.strip());
        if (matcher.matches()) {
            return check(matcher.group(1), Hashes.sha256Hex(matcher.group(2)));
        }
        return checkBootstrap(presented.strip());
    }

    public boolean revoke(UUID id) {
        return jdbc.sql(
                                "update api_key set revoked_at = :now where id = :id and revoked_at is null")
                        .param("now", Timestamp.from(clock.instant()))
                        .param("id", id)
                        .update()
                > 0;
    }

    public List<Summary> list(UUID installationId) {
        return jdbc.sql(
                        """
                        select id, installation_id, name, prefix, scopes, revoked_at
                        from api_key where installation_id is not distinct from :installation
                        order by created_at
                        """)
                .param("installation", installationId)
                .query(
                        (rs, row) ->
                                new Summary(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("installation_id", UUID.class),
                                        rs.getString("name"),
                                        rs.getString("prefix"),
                                        scopes(rs.getArray("scopes")),
                                        rs.getTimestamp("revoked_at") != null))
                .list();
    }

    private Optional<Principal> checkBootstrap(String presented) {
        if (presented.length() < MINIMUM_BOOTSTRAP_LENGTH) {
            Hashes.constantTimeEquals(DECOY_HASH, Hashes.sha256Hex(presented));
            return Optional.empty();
        }
        String hash = Hashes.sha256Hex(presented);
        return check("boot" + hash.substring(0, 6), hash);
    }

    private Optional<Principal> check(String prefix, String presentedHash) {
        Optional<StoredKey> stored =
                jdbc.sql(
                                "select id, installation_id, name, scopes, hash, revoked_at from api_key"
                                        + " where prefix = :prefix")
                        .param("prefix", prefix)
                        .query(
                                (rs, row) ->
                                        new StoredKey(
                                                principal(rs),
                                                rs.getString("hash"),
                                                rs.getTimestamp("revoked_at") != null))
                        .optional();
        String expected = stored.map(StoredKey::hash).orElse(DECOY_HASH);
        boolean match = Hashes.constantTimeEquals(expected, presentedHash);
        if (stored.isEmpty() || !match || stored.get().revoked()) {
            return Optional.empty();
        }
        jdbc.sql("update api_key set last_used_at = :now where id = :id")
                .param("now", Timestamp.from(clock.instant()))
                .param("id", stored.get().principal().keyId())
                .update();
        return Optional.of(stored.get().principal());
    }

    private record StoredKey(Principal principal, String hash, boolean revoked) {}

    private void insert(
            UUID installationId, String name, String prefix, String hash, Set<Scope> scopes) {
        String[] names = scopes.stream().map(Scope::name).sorted().toArray(String[]::new);
        jdbc.sql(
                        """
                        insert into api_key (id, installation_id, name, prefix, hash, scopes)
                        values (:id, :installation, :name, :prefix, :hash, :scopes)
                        on conflict (prefix) do nothing
                        """)
                .param("id", Ids.newId())
                .param("installation", installationId)
                .param("name", name)
                .param("prefix", prefix)
                .param("hash", hash)
                .param("scopes", names)
                .update();
    }

    private String randomPrefix() {
        StringBuilder prefix = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            prefix.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return prefix.toString();
    }

    private static Principal principal(ResultSet rs) throws SQLException {
        return new Principal(
                rs.getObject("id", UUID.class),
                rs.getObject("installation_id", UUID.class),
                rs.getString("name"),
                scopes(rs.getArray("scopes")));
    }

    private static Set<Scope> scopes(Array array) throws SQLException {
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        for (Object value : (Object[]) array.getArray()) {
            scopes.add(Scope.valueOf(value.toString()));
        }
        return scopes;
    }
}
