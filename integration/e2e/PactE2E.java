import io.github.pactproject.app.PactApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;

/** Runs apply and cleanup with the same core, retaining its previous state. */
class PactE2E {
    public static void main(String[] args) throws Exception {
        Path template = Path.of(args[0]);
        Path desired = Path.of(args[1]);
        Path empty = Path.of(args[2]);
        Path plugins = Path.of(args[3]);
        Path directory = Files.createTempDirectory("pact-e2e-");
        Path state = directory.resolve("state.yaml");
        Path config = directory.resolve("config.yaml");
        try {
            String source = Files.readString(template);
            if (!source.contains(desired.toString())) {
                throw new IllegalArgumentException("E2E config does not reference the desired-state fixture");
            }
            Files.writeString(config, source.replace(desired.toString(), state.toString()));
            Files.copy(desired, state, StandardCopyOption.REPLACE_EXISTING);
            try (PactApplication application = PactApplication.create(config, plugins)) {
                try {
                    application.run();
                }
                finally {
                    Files.copy(empty, state, StandardCopyOption.REPLACE_EXISTING);
                    application.run();
                }
            }
            try (var connection = DriverManager.getConnection(
                    "jdbc:postgresql://pact-postgres:5432/postgres",
                    System.getenv("PACT_POSTGRES_USER"), System.getenv("PACT_POSTGRES_PASSWORD"));
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT count(*) FROM pg_database d
                         CROSS JOIN LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
                         JOIN pg_roles r ON r.oid = a.grantee
                         WHERE d.datname = 'pact_it' AND r.rolname = 'pact_it_user'
                         """)) {
                rows.next();
                if (rows.getInt(1) != 0) {
                    throw new IllegalStateException("PostgreSQL E2E grants remain after cleanup");
                }
            }
        }
        finally {
            Files.deleteIfExists(config);
            Files.deleteIfExists(state);
            Files.deleteIfExists(directory);
        }
    }
}
