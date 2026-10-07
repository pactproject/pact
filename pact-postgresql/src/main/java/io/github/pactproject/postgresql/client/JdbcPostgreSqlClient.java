package io.github.pactproject.postgresql.client;

import io.github.pactproject.postgresql.PostgreSqlConfig;
import io.github.pactproject.postgresql.PostgreSqlReconciliationMode;
import io.github.pactproject.postgresql.api.PostgreSqlClient;
import io.github.pactproject.postgresql.api.PostgreSqlClientException;
import io.github.pactproject.api.Identity;
import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;
import io.github.pactproject.postgresql.model.DefaultPrivilegeOverride;
import io.github.pactproject.postgresql.model.DefaultPrivilegeScope;
import io.github.pactproject.postgresql.model.DefaultPrivilegeType;
import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.GrantLevel;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.RoutineSignature;
import io.github.pactproject.postgresql.model.Privilege;
import io.github.pactproject.postgresql.sync.GrantDiff;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdbcPostgreSqlClient implements PostgreSqlClient {
    private static final Pattern JDBC_URL = Pattern.compile(
            "^(jdbc:postgresql:(?://[^/?]*)?)(?:/?([^?]*))?(\\?.*)?$"
    );

    private static final String ACL_GRANTEE =
            "CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE grantee.rolname END";
    private static final String USER_SCHEMA =
            "n.nspname NOT LIKE 'pg!_%' ESCAPE '!' "
                    + "AND n.nspname <> 'information_schema'";
    private static final String RELATION_KINDS =
            "c.relkind IN ('r', 'p', 'v', 'm', 'f')";

    private static final String DATABASE_GRANTS = "SELECT "
            + ACL_GRANTEE + """
            , acl.privilege_type,
                   acl.is_grantable, grantor.rolname,
                   acl.grantor = d.datdba, acl.grantee = d.datdba
            FROM pg_catalog.pg_database AS d
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(d.datacl, pg_catalog.acldefault('d', d.datdba))
            ) AS acl
            LEFT JOIN pg_catalog.pg_roles AS grantee
                ON grantee.oid = acl.grantee
            JOIN pg_catalog.pg_roles AS grantor ON grantor.oid = acl.grantor
            WHERE d.datname = current_database()""";

    private static final String SCHEMA_GRANTS = "SELECT n.nspname, "
            + ACL_GRANTEE + """
            ,
                   acl.privilege_type, acl.is_grantable, grantor.rolname,
                   acl.grantor = n.nspowner, acl.grantee = n.nspowner
            FROM pg_catalog.pg_namespace AS n
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(n.nspacl, pg_catalog.acldefault('n', n.nspowner))
            ) AS acl
            LEFT JOIN pg_catalog.pg_roles AS grantee
                ON grantee.oid = acl.grantee
            JOIN pg_catalog.pg_roles AS grantor ON grantor.oid = acl.grantor
            WHERE\s""" + USER_SCHEMA;

    private static final String TABLE_GRANTS =
            "SELECT n.nspname, c.relname, " + ACL_GRANTEE + """
            ,
                   acl.privilege_type, acl.is_grantable, grantor.rolname,
                   acl.grantor = c.relowner, acl.grantee = c.relowner
            FROM pg_catalog.pg_class AS c
            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(c.relacl, pg_catalog.acldefault('r', c.relowner))
            ) AS acl
            LEFT JOIN pg_catalog.pg_roles AS grantee
                ON grantee.oid = acl.grantee
            JOIN pg_catalog.pg_roles AS grantor ON grantor.oid = acl.grantor
            WHERE\s""" + RELATION_KINDS + " AND " + USER_SCHEMA;

    private static final String COLUMN_GRANTS =
            "SELECT n.nspname, c.relname, a.attname, " + ACL_GRANTEE + """
            ,
                   acl.privilege_type, acl.is_grantable, grantor.rolname,
                   acl.grantor = c.relowner, acl.grantee = c.relowner
            FROM pg_catalog.pg_attribute AS a
            JOIN pg_catalog.pg_class AS c ON c.oid = a.attrelid
            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(a.attacl) AS acl
            LEFT JOIN pg_catalog.pg_roles AS grantee
                ON grantee.oid = acl.grantee
            JOIN pg_catalog.pg_roles AS grantor ON grantor.oid = acl.grantor
            WHERE a.attnum > 0 AND NOT a.attisdropped AND\s"""
            + RELATION_KINDS + " AND " + USER_SCHEMA;

    private static final String SEQUENCE_GRANTS =
            "SELECT n.nspname, c.relname, " + ACL_GRANTEE + """
            ,
                   acl.privilege_type, acl.is_grantable, grantor.rolname,
                   acl.grantor = c.relowner, acl.grantee = c.relowner
            FROM pg_catalog.pg_class AS c
            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(c.relacl, pg_catalog.acldefault('S', c.relowner))
            ) AS acl
            LEFT JOIN pg_catalog.pg_roles AS grantee
                ON grantee.oid = acl.grantee
            JOIN pg_catalog.pg_roles AS grantor ON grantor.oid = acl.grantor
            WHERE c.relkind = 'S' AND\s""" + USER_SCHEMA;

    private static final String FUNCTION_GRANTS =
            "SELECT n.nspname, p.proname, "
                   + "pg_catalog.pg_get_function_identity_arguments(p.oid), "
                   + ACL_GRANTEE + """
            , acl.privilege_type,
                   acl.is_grantable, grantor.rolname,
                   acl.grantor = p.proowner, acl.grantee = p.proowner
            FROM pg_catalog.pg_proc AS p
            JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(p.proacl, pg_catalog.acldefault('f', p.proowner))
            ) AS acl
            LEFT JOIN pg_catalog.pg_roles AS grantee
                ON grantee.oid = acl.grantee
            JOIN pg_catalog.pg_roles AS grantor ON grantor.oid = acl.grantor
            WHERE p.prokind IN ('f', 'a', 'w') AND\s""" + USER_SCHEMA;

    private static final String PROCEDURE_GRANTS =
            "SELECT n.nspname, p.proname, "
                   + "pg_catalog.pg_get_function_identity_arguments(p.oid), "
                   + ACL_GRANTEE + """
            , acl.privilege_type,
                   acl.is_grantable, grantor.rolname,
                   acl.grantor = p.proowner, acl.grantee = p.proowner
            FROM pg_catalog.pg_proc AS p
            JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(p.proacl, pg_catalog.acldefault('f', p.proowner))
            ) AS acl
            LEFT JOIN pg_catalog.pg_roles AS grantee
                ON grantee.oid = acl.grantee
            JOIN pg_catalog.pg_roles AS grantor ON grantor.oid = acl.grantor
            WHERE p.prokind = 'p' AND\s""" + USER_SCHEMA;

    private static final String LIST_SCHEMAS =
            "SELECT n.nspname FROM pg_catalog.pg_namespace AS n WHERE "
                    + USER_SCHEMA;
    private static final String LIST_TABLES =
            "SELECT c.relname, owner.rolname "
                    + "FROM pg_catalog.pg_class AS c "
                    + "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
                    + "JOIN pg_catalog.pg_roles AS owner ON owner.oid = c.relowner "
                    + "WHERE n.nspname = ? AND " + RELATION_KINDS;
    private static final String LIST_SEQUENCES =
            "SELECT c.relname, owner.rolname "
                    + "FROM pg_catalog.pg_class AS c "
                    + "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
                    + "JOIN pg_catalog.pg_roles AS owner ON owner.oid = c.relowner "
                    + "WHERE n.nspname = ? AND c.relkind = 'S'";
    private static final String LIST_ROUTINES = """
            SELECT p.proname,
                   pg_catalog.pg_get_function_identity_arguments(p.oid),
                   p.prokind,
                   owner.rolname
            FROM pg_catalog.pg_proc AS p
            JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
            JOIN pg_catalog.pg_roles AS owner ON owner.oid = p.proowner
            WHERE n.nspname = ? AND p.prokind IN ('f', 'a', 'w', 'p')
            """;
    private static final String DEFAULT_PRIVILEGE_ACL = """
            SELECT CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE grantee.rolname END,
                   acl.privilege_type, acl.is_grantable, grantor.rolname,
                   acl.grantee = owner.oid
            FROM pg_catalog.pg_default_acl AS d
            JOIN pg_catalog.pg_roles AS owner ON owner.oid = d.defaclrole
            JOIN pg_catalog.pg_namespace AS n ON n.oid = d.defaclnamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(d.defaclacl) AS acl
            LEFT JOIN pg_catalog.pg_roles AS grantee
                ON grantee.oid = acl.grantee
            JOIN pg_catalog.pg_roles AS grantor ON grantor.oid = acl.grantor
            WHERE owner.rolname = ? AND n.nspname = ? AND d.defaclobjtype = ?
            """;

    private final PostgreSqlConfig config;

    public JdbcPostgreSqlClient(PostgreSqlConfig config) {
        this.config = config;
    }

    @Override
    public Set<Grant> getManagedGrants(Set<String> databases)
            throws PostgreSqlClientException {
        Set<Grant> result = new HashSet<>();
        for (String database : databases) {
            try (Connection connection = connect(database)) {
                verifyAuthoritativeSuperuser(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET search_path TO pg_catalog");
                }
                result.addAll(loadManagedGrants(connection, database));
            }
            catch (SQLException e) {
                throw new PostgreSqlClientException(
                        "Failed to read PACT-owned PostgreSQL grants in database '"
                                + database + "'",
                        e
                );
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public Set<DefaultPrivilegeGrant> getManagedDefaultPrivileges(
            Set<DefaultPrivilegeScope> scopes
    ) throws PostgreSqlClientException {
        Set<DefaultPrivilegeGrant> result = new HashSet<>();
        Set<String> databases = scopes.stream()
                .map(DefaultPrivilegeScope::database)
                .collect(java.util.stream.Collectors.toSet());
        for (String database : databases) {
            Set<DefaultPrivilegeScope> databaseScopes = scopes.stream()
                    .filter(scope -> scope.database().equals(database))
                    .collect(java.util.stream.Collectors.toSet());
            try (Connection connection = connect(database)) {
                verifyAuthoritativeSuperuser(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET search_path TO pg_catalog");
                }
                for (AclDefaultPrivilege acl
                        : loadAclDefaultPrivileges(connection, databaseScopes)) {
                    if (isManagedDefaultPrivilege(acl, database)) {
                        result.add(acl.grant());
                    }
                }
            }
            catch (SQLException e) {
                throw new PostgreSqlClientException(
                        "Failed to read PACT-owned PostgreSQL default "
                                + "privileges in database '" + database + "'",
                        e
                );
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public void reconcileIdentities(
            Set<Identity> previous,
            Set<Identity> desired
    ) throws PostgreSqlClientException {
        if (desired.isEmpty()) {
            return;
        }
        for (Identity identity : desired) {
            if (identity.passwordSource() != null
                    && identity.password() == null) {
                throw new PostgreSqlClientException(
                        "Password Secret was not resolved for PostgreSQL role '"
                                + identity.principal() + "'"
                );
            }
        }

        Map<String, Identity> previousByPrincipal = new java.util.HashMap<>();
        for (Identity identity : previous) {
            previousByPrincipal.put(identity.principal(), identity);
        }

        try (Connection connection = connectConfiguredDatabase()) {
            verifyAuthoritativeSuperuser(connection);
            connection.setAutoCommit(false);
            try {
                List<Identity> ordered = desired.stream()
                        .sorted(java.util.Comparator.comparing(
                                Identity::principal
                        ))
                        .toList();
                for (Identity identity : ordered) {
                    reconcileIdentity(
                            connection,
                            previousByPrincipal.get(identity.principal()),
                            identity
                    );
                }
                connection.commit();
            }
            catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        }
        catch (SQLException e) {
            throw new PostgreSqlClientException(
                    "Failed to reconcile PostgreSQL identities",
                    e
            );
        }
    }

    @Override
    public void synchronize(
            Set<String> databases,
            Set<Grant> desired,
            Set<DefaultPrivilegeGrant> desiredDefaults,
            Set<DefaultPrivilegeScope> defaultScopes,
            Set<DefaultPrivilegeOverride> overrides
    )
            throws PostgreSqlClientException {
        for (Grant grant : desired) {
            if (!databases.contains(grant.database())) {
                throw new PostgreSqlClientException(
                        "Desired grant is outside its PostgreSQL reconciliation scope"
                );
            }
        }
        for (DefaultPrivilegeScope scope : defaultScopes) {
            if (!databases.contains(scope.database())) {
                throw new PostgreSqlClientException(
                        "Default privilege scope is outside its PostgreSQL "
                                + "reconciliation scope"
                );
            }
        }
        for (DefaultPrivilegeGrant grant : desiredDefaults) {
            if (!defaultScopes.contains(grant.scope())) {
                throw new PostgreSqlClientException(
                        "Desired default privilege is outside its PostgreSQL "
                                + "reconciliation scope"
                );
            }
        }
        // Each database needs its own connection, so the sync is atomic per
        // database only; the backend transaction compensates across databases.
        for (String database : databases) {
            try (Connection connection = connect(database)) {
                verifyAuthoritativeSuperuser(connection);
                connection.setAutoCommit(false);
                try {
                    synchronizeDatabase(
                            connection,
                            database,
                            desired,
                            desiredDefaults,
                            defaultScopes,
                            overrides
                    );
                    connection.commit();
                }
                catch (SQLException | RuntimeException e) {
                    rollback(connection, e);
                    throw e;
                }
            }
            catch (SQLException e) {
                throw new PostgreSqlClientException(
                        "Failed to synchronize PostgreSQL grants in database '"
                                + database + "'",
                        e
                );
            }
        }
    }

    private void synchronizeDatabase(
            Connection connection,
            String database,
            Set<Grant> desired,
            Set<DefaultPrivilegeGrant> desiredDefaults,
            Set<DefaultPrivilegeScope> defaultScopes,
            Set<DefaultPrivilegeOverride> overrides
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL search_path TO pg_catalog");
        }
        List<Grant> databaseGrants = desired.stream()
                .filter(grant -> grant.database().equals(database))
                .toList();
        Set<Grant> wanted = expand(
                connection,
                database,
                databaseGrants
        );
        Set<DefaultPrivilegeScope> databaseDefaultScopes = defaultScopes.stream()
                .filter(scope -> scope.database().equals(database))
                .collect(java.util.stream.Collectors.toSet());
        Set<DefaultPrivilegeGrant> databaseDefaults = desiredDefaults.stream()
                .filter(grant -> grant.scope().database().equals(database))
                .collect(java.util.stream.Collectors.toSet());
        Set<Grant> inherited = expandDefaultPrivileges(
                connection,
                database,
                databaseDefaults
        );
        inherited.removeIf(grant -> overrides.stream()
                .anyMatch(override -> override.matches(grant)));
        wanted.addAll(inherited);

        if (config.reconciliationMode()
                == PostgreSqlReconciliationMode.AUTHORITATIVE) {
            synchronizeAuthoritativeDatabase(connection, database, wanted);
        }
        else {
            if (wanted.stream().anyMatch(grant -> "PUBLIC".equals(grant.role()))) {
                throw new SQLException(
                        "PostgreSQL PUBLIC grants require authoritative "
                                + "reconciliation mode"
                );
            }
            Set<Grant> actual = loadManagedGrants(connection, database);
            GrantDiff.Result diff = GrantDiff.diff(actual, wanted);
            if (!diff.delete().isEmpty() || !diff.create().isEmpty()) {
                for (Grant grant : diff.delete()) {
                    execute(connection, "REVOKE", grant);
                }
                actual = loadManagedGrants(connection, database);
                for (Grant grant : GrantDiff.diff(actual, wanted).create()) {
                    execute(connection, "GRANT", grant);
                }
                GrantDiff.Result remaining = GrantDiff.diff(
                        loadManagedGrants(connection, database),
                        wanted
                );
                if (!remaining.create().isEmpty()
                        || !remaining.delete().isEmpty()) {
                    throw new SQLException(
                            "PostgreSQL did not apply the complete desired ACL "
                                    + "in database '" + database + "' ("
                                    + remaining.create().size()
                                    + " grant(s) missing, "
                                    + remaining.delete().size()
                                    + " stale grant(s) remain); verify the "
                                    + "grantor's authority and GRANT OPTION"
                    );
                }
            }
        }
        synchronizeDefaultPrivileges(
                connection,
                database,
                databaseDefaults,
                databaseDefaultScopes
        );
    }

    private void synchronizeDefaultPrivileges(
            Connection connection,
            String database,
            Set<DefaultPrivilegeGrant> desired,
            Set<DefaultPrivilegeScope> scopes
    ) throws SQLException {
        if (scopes.isEmpty()) {
            return;
        }
        if (config.reconciliationMode()
                == PostgreSqlReconciliationMode.GRANTOR
                && desired.stream().anyMatch(
                        grant -> "PUBLIC".equals(grant.role()))) {
            throw new SQLException(
                    "PostgreSQL PUBLIC default privileges require "
                            + "authoritative reconciliation mode"
            );
        }

        List<AclDefaultPrivilege> revocations = new ArrayList<>();
        for (AclDefaultPrivilege acl
                : loadAclDefaultPrivileges(connection, scopes)) {
            if (isManagedDefaultPrivilege(acl, database)
                    && (!desired.contains(acl.grant()) || acl.grantable())) {
                revocations.add(acl);
            }
        }
        revocations.sort(java.util.Comparator
                .comparing(AclDefaultPrivilege::grantor)
                .thenComparing(acl -> acl.grant().toString()));
        for (AclDefaultPrivilege acl : revocations) {
            executeDefaultPrivilege(
                    connection,
                    "REVOKE",
                    acl.grant(),
                    acl.grantable() && desired.contains(acl.grant()),
                    acl.grantor()
            );
        }

        Set<DefaultPrivilegeGrant> actual = managedDefaultPrivileges(
                loadAclDefaultPrivileges(connection, scopes),
                database
        );
        for (DefaultPrivilegeGrant grant : desired.stream()
                .sorted(java.util.Comparator.comparing(
                        DefaultPrivilegeGrant::toString
                ))
                .toList()) {
            if (!actual.contains(grant)) {
                executeDefaultPrivilege(
                        connection, "GRANT", grant, false, null
                );
            }
        }

        Set<AclDefaultPrivilege> finalAcl =
                loadAclDefaultPrivileges(connection, scopes);
        Set<DefaultPrivilegeGrant> finalPrivileges =
                managedDefaultPrivileges(finalAcl, database);
        Set<DefaultPrivilegeGrant> missing = new HashSet<>(desired);
        missing.removeAll(finalPrivileges);
        Set<DefaultPrivilegeGrant> stale =
                new HashSet<>(finalPrivileges);
        stale.removeAll(desired);
        long grantOptions = 0;
        for (AclDefaultPrivilege acl : finalAcl) {
            if (isManagedDefaultPrivilege(acl, database) && acl.grantable()) {
                grantOptions++;
            }
        }
        if (!missing.isEmpty() || !stale.isEmpty() || grantOptions > 0) {
            throw new SQLException(
                    "PostgreSQL default privilege reconciliation is "
                            + "incomplete in database '" + database + "' ("
                            + missing.size() + " missing, "
                            + stale.size() + " stale, "
                            + grantOptions + " grant option(s) remain)"
            );
        }
    }

    private Set<DefaultPrivilegeGrant> managedDefaultPrivileges(
            Set<AclDefaultPrivilege> acl,
            String database
    ) throws SQLException {
        Set<DefaultPrivilegeGrant> result = new HashSet<>();
        for (AclDefaultPrivilege entry : acl) {
            if (isManagedDefaultPrivilege(entry, database)) {
                result.add(entry.grant());
            }
        }
        return result;
    }

    private void executeDefaultPrivilege(
            Connection connection,
            String operation,
            DefaultPrivilegeGrant grant,
            boolean grantOptionOnly,
            String grantor
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (grantor != null) {
                statement.execute(
                        "SET LOCAL ROLE " + quoteIdentifier(grantor)
                );
            }
            statement.execute(defaultPrivilegeStatement(
                    operation, grant, grantOptionOnly
            ));
            if (grantor != null) {
                statement.execute("SET LOCAL ROLE NONE");
            }
        }
    }

    static String defaultPrivilegeStatement(
            String operation,
            DefaultPrivilegeGrant grant,
            boolean grantOptionOnly
    ) {
        DefaultPrivilegeScope scope = grant.scope();
        String prefix = "ALTER DEFAULT PRIVILEGES FOR ROLE "
                + quoteIdentifier(scope.creator()) + " IN SCHEMA "
                + quoteIdentifier(scope.schema()) + " ";
        String action = operation + " ";
        if (grantOptionOnly) {
            action += "GRANT OPTION FOR ";
        }
        action += grant.privilege().name() + " ON "
                + scope.type().sqlType()
                + ("GRANT".equals(operation) ? " TO " : " FROM ")
                + grantee(grant.role());
        return prefix + action;
    }

    private void synchronizeAuthoritativeDatabase(
            Connection connection,
            String database,
            Set<Grant> wanted
    )     throws SQLException {
    Set<AclGrant> actual = loadAclGrants(connection, database);
    List<AclGrant> revocations = actual.stream()
            .filter(acl -> !acl.granteeIsOwner()
                    && !isPreservedDefaultPublicAcl(acl))
            .filter(acl -> !wanted.contains(acl.grant())
                    || acl.grantable())
            .sorted(java.util.Comparator
                    .comparingInt((AclGrant acl) -> revokeOrder(
                            acl.grant().target().level()
                    ))
                    .reversed()
                    .thenComparing(AclGrant::grantor)
                    .thenComparing(acl -> acl.grant().toString()))
            .toList();
    for (AclGrant acl : revocations) {
        if (!wanted.contains(acl.grant())) {
            executeRevokeAsGrantor(connection, acl, false);
        }
        else if (acl.grantable()) {
            executeRevokeAsGrantor(connection, acl, true);
        }
    }

    Set<Grant> actualGrants = authoritativeGrants(
            loadAclGrants(connection, database)
    );
    for (Grant grant : wanted) {
        if (!actualGrants.contains(grant)) {
            execute(connection, "GRANT", grant);
        }
    }

    Set<AclGrant> finalAcl = loadAclGrants(connection, database);
        Set<Grant> finalGrants = authoritativeGrants(finalAcl);
        Set<Grant> missing = new HashSet<>(wanted);
        missing.removeAll(finalGrants);
    Set<Grant> stale = authoritativeManagedGrants(finalAcl);
        stale.removeAll(wanted);
        long grantOptions = finalAcl.stream()
                .filter(acl -> !acl.granteeIsOwner()
                        && !isPreservedDefaultPublicAcl(acl)
                        && acl.grantable())
                .count();
        if (!missing.isEmpty() || !stale.isEmpty() || grantOptions > 0) {
            throw new SQLException(
                    "PostgreSQL authoritative ACL reconciliation is incomplete "
                            + "in database '" + database + "' ("
                            + missing.size() + " missing grant(s), "
                            + stale.size() + " stale grant(s), "
                            + grantOptions + " grant option(s) remain)"
            );
        }
    }

    private static int revokeOrder(GrantLevel level) {
        return switch (level) {
            case COLUMN -> 4;
            case TABLE, SEQUENCE, FUNCTION, PROCEDURE -> 3;
            case SCHEMA -> 2;
            case DATABASE -> 1;
        };
    }

    private Set<Grant> authoritativeGrants(Set<AclGrant> aclGrants) {
        Set<Grant> result = new HashSet<>();
        for (AclGrant acl : aclGrants) {
            if (!acl.granteeIsOwner()) {
                result.add(acl.grant());
            }
        }
        return result;
    }

    private Set<Grant> authoritativeManagedGrants(Set<AclGrant> aclGrants) {
        Set<Grant> result = new HashSet<>();
        for (AclGrant acl : aclGrants) {
            if (!acl.granteeIsOwner()
                    && !isPreservedDefaultPublicAcl(acl)) {
                result.add(acl.grant());
            }
        }
        return result;
    }

    private void executeRevokeAsGrantor(
            Connection connection,
            AclGrant acl,
            boolean grantOptionOnly
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "SET LOCAL ROLE " + quoteIdentifier(acl.grantor())
            );
            statement.execute(
                    revokeStatement(acl.grant(), grantOptionOnly) + " CASCADE"
            );
            statement.execute("SET LOCAL ROLE NONE");
        }
    }

    private void verifyAuthoritativeSuperuser(Connection connection)
            throws SQLException {
        if (config.reconciliationMode()
                != PostgreSqlReconciliationMode.AUTHORITATIVE) {
            return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT rolsuper FROM pg_catalog.pg_roles "
                             + "WHERE rolname = current_user"
             )) {
            if (!rows.next() || !rows.getBoolean(1)) {
                throw new SQLException(
                        "PostgreSQL authoritative reconciliation requires "
                                + "a superuser login"
                );
            }
        }
    }

    static String jdbcUrlFor(String jdbcUrl, String database) {
        Matcher matcher = JDBC_URL.matcher(jdbcUrl);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported PostgreSQL jdbc-url");
        }
        String prefix = matcher.group(1);
        String query = matcher.group(3) == null ? "" : matcher.group(3);
        String encoded = URLEncoder.encode(database, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String separator = prefix.contains("//") ? "/" : "";
        return prefix + separator + encoded + query;
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrlFor(config.jdbcUrl(), database),
                config.username(),
                config.password()
        );
    }

    private Connection connectConfiguredDatabase() throws SQLException {
        return DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
        );
    }

    private void reconcileIdentity(
            Connection connection,
            Identity previous,
            Identity desired
    ) throws SQLException {
        Boolean canLogin = roleCanLogin(connection, desired.principal());
        if (canLogin == null) {
            if (!desired.ensure()) {
                throw new SQLException(
                        "PostgreSQL role '" + desired.principal()
                                + "' does not exist; set ensure: true to create it"
                );
            }
            String sql = "CREATE ROLE " + quoteIdentifier(desired.principal())
                    + " LOGIN"
                    + (desired.password() == null
                    ? ""
                    : " PASSWORD " + quotePassword(desired.password().reveal()));
            executeIdentityDdl(
                    connection,
                    sql,
                    desired.principal(),
                    "create"
            );
            return;
        }

        if (desired.password() != null
                && !canLogin) {
            throw new SQLException(
                    "PostgreSQL role '" + desired.principal()
                            + "' does not have LOGIN; PACT does not alter existing role attributes"
            );
        }
        if (desired.password() != null
                && passwordChanged(previous, desired)) {
            String sql = "ALTER ROLE " + quoteIdentifier(desired.principal())
                    + " PASSWORD " + quotePassword(desired.password().reveal());
            executeIdentityDdl(
                    connection,
                    sql,
                    desired.principal(),
                    "set password for"
            );
        }
    }

    private Boolean roleCanLogin(Connection connection, String role)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rolcanlogin FROM pg_catalog.pg_roles WHERE rolname = ?"
        )) {
            statement.setString(1, role);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getBoolean(1) : null;
            }
        }
    }

    private static boolean passwordChanged(
            Identity previous,
            Identity desired
    ) {
        return previous == null
                || !java.util.Objects.equals(
                        previous.passwordSource(),
                        desired.passwordSource()
                )
                || !java.util.Objects.equals(
                        previous.passwordVersion(),
                        desired.passwordVersion()
                );
    }

    private static void executeIdentityDdl(
            Connection connection,
            String sql,
            String role,
            String operation
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        catch (SQLException e) {
            throw new SQLException(
                    "Failed to " + operation + " PostgreSQL role '"
                            + role + "' (SQLState " + e.getSQLState() + ")",
                    e.getSQLState()
            );
        }
    }

    static String quotePassword(String password) throws SQLException {
        if (password.indexOf('\0') >= 0) {
            throw new SQLException(
                    "PostgreSQL passwords cannot contain a zero byte"
            );
        }
        StringBuilder literal = new StringBuilder("E'");
        for (int i = 0; i < password.length(); i++) {
            char current = password.charAt(i);
            if (current == '\\') {
                literal.append("\\\\");
            }
            else if (current == '\'') {
                literal.append("''");
            }
            else {
                literal.append(current);
            }
        }
        return literal.append('\'').toString();
    }

    private Set<Grant> loadManagedGrants(Connection connection, String database)
            throws SQLException {
        Set<Grant> result = new HashSet<>();
        for (AclGrant acl : loadAclGrants(connection, database)) {
            Grant grant = acl.grant();
            if (config.reconciliationMode()
                    == PostgreSqlReconciliationMode.AUTHORITATIVE) {
                if (!acl.granteeIsOwner()
                        && !isPreservedDefaultPublicAcl(acl)) {
                    result.add(grant);
                }
                continue;
            }

            if (!config.username().equals(acl.grantor())
                    || config.username().equals(grant.role())
                    || "PUBLIC".equals(grant.role())) {
                continue;
            }
            if (acl.grantable()) {
                throw new SQLException(
                        "PACT grantor has a grant-option privilege on "
                                + grant.target().level().key()
                                + " in database '" + database
                                + "'; grant options are outside the managed contract"
                );
            }
            result.add(grant);
        }
        return Set.copyOf(result);
    }

    private Set<AclGrant> loadAclGrants(
            Connection connection,
            String database
    ) throws SQLException {
        Set<AclGrant> result = new HashSet<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(DATABASE_GRANTS)) {
                while (rows.next()) {
                    result.add(aclGrant(
                            new GrantTarget(database, null, null, null),
                            rows.getString(1),
                            rows.getString(2),
                            rows.getBoolean(3),
                            rows.getString(4),
                            rows.getBoolean(5),
                            rows.getBoolean(6)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(SCHEMA_GRANTS)) {
                while (rows.next()) {
                    result.add(aclGrant(
                            new GrantTarget(database, rows.getString(1), null, null),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getBoolean(4),
                            rows.getString(5),
                            rows.getBoolean(6),
                            rows.getBoolean(7)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(TABLE_GRANTS)) {
                while (rows.next()) {
                    result.add(aclGrant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    rows.getString(2),
                                    null
                            ),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getBoolean(5),
                            rows.getString(6),
                            rows.getBoolean(7),
                            rows.getBoolean(8)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(COLUMN_GRANTS)) {
                while (rows.next()) {
                    result.add(aclGrant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    rows.getString(2),
                                    rows.getString(3)
                            ),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getBoolean(6),
                            rows.getString(7),
                            rows.getBoolean(8),
                            rows.getBoolean(9)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(SEQUENCE_GRANTS)) {
                while (rows.next()) {
                    result.add(aclGrant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    null,
                                    null,
                                    rows.getString(2)
                            ),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getBoolean(5),
                            rows.getString(6),
                            rows.getBoolean(7),
                            rows.getBoolean(8)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(FUNCTION_GRANTS)) {
                while (rows.next()) {
                    result.add(aclGrant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    null,
                                    null,
                                    null,
                                    RoutineSignature.parse(
                                            quoteIdentifier(rows.getString(2))
                                                    + "(" + rows.getString(3) + ")"
                                    ),
                                    null
                            ),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getBoolean(6),
                            rows.getString(7),
                            rows.getBoolean(8),
                            rows.getBoolean(9)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(PROCEDURE_GRANTS)) {
                while (rows.next()) {
                    result.add(aclGrant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    null,
                                    null,
                                    null,
                                    null,
                                    RoutineSignature.parse(
                                            quoteIdentifier(rows.getString(2))
                                                    + "(" + rows.getString(3) + ")"
                                    )
                            ),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getBoolean(6),
                            rows.getString(7),
                            rows.getBoolean(8),
                            rows.getBoolean(9)
                    ));
                }
            }
        }
        return Set.copyOf(result);
    }

    private Set<AclDefaultPrivilege> loadAclDefaultPrivileges(
            Connection connection,
            Set<DefaultPrivilegeScope> scopes
    ) throws SQLException {
        Set<AclDefaultPrivilege> result = new HashSet<>();
        for (DefaultPrivilegeScope scope : scopes) {
            try (PreparedStatement statement =
                         connection.prepareStatement(DEFAULT_PRIVILEGE_ACL)) {
                statement.setString(1, scope.creator());
                statement.setString(2, scope.schema());
                statement.setString(3, scope.type().catalogType());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String privilegeName = rows.getString(2);
                        Privilege privilege;
                        try {
                            privilege = Privilege.parse(
                                    scope.type().privilegeLevel(),
                                    privilegeName
                            );
                        }
                        catch (IllegalArgumentException e) {
                            throw new SQLException(
                                    "Unsupported PostgreSQL default ACL "
                                            + "privilege: " + privilegeName,
                                    e
                            );
                        }
                        result.add(new AclDefaultPrivilege(
                                new DefaultPrivilegeGrant(
                                        scope,
                                        rows.getString(1),
                                        privilege
                                ),
                                rows.getString(4),
                                rows.getBoolean(3),
                                rows.getBoolean(5)
                        ));
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    private boolean isManagedDefaultPrivilege(
            AclDefaultPrivilege acl,
            String database
    ) throws SQLException {
        if (acl.granteeIsOwner()) {
            return false;
        }
        if (config.reconciliationMode()
                == PostgreSqlReconciliationMode.AUTHORITATIVE) {
            return true;
        }
        DefaultPrivilegeGrant grant = acl.grant();
        if (!config.username().equals(acl.grantor())
                || config.username().equals(grant.role())
                || "PUBLIC".equals(grant.role())) {
            return false;
        }
        if (acl.grantable()) {
            throw new SQLException(
                    "PACT grantor has a grant-option default privilege in "
                            + "database '" + database + "'; grant options are "
                            + "outside the managed contract"
            );
        }
        return true;
    }

    private static AclGrant aclGrant(
            GrantTarget target,
            String role,
            String privilege,
            boolean grantable,
            String grantor,
            boolean grantorIsOwner,
            boolean granteeIsOwner
    ) throws SQLException {
        try {
            return new AclGrant(
                    new Grant(
                            target,
                            role,
                            Privilege.parse(target.level(), privilege)
                    ),
                    grantor,
                    grantable,
                    grantorIsOwner,
                    granteeIsOwner
            );
        }
        catch (IllegalArgumentException e) {
            throw new SQLException(
                    "Unsupported PostgreSQL ACL privilege: " + privilege, e
            );
        }
    }

    private boolean isPreservedDefaultPublicAcl(AclGrant acl) {
        if (!config.preserveDefaultPublicPrivileges()
                || !acl.grantorIsOwner()
                || acl.grantable()
                || !"PUBLIC".equals(acl.grant().role())) {
            return false;
        }
        Grant grant = acl.grant();
        return switch (grant.target().level()) {
            case DATABASE -> grant.privilege() == Privilege.CONNECT
                    || grant.privilege() == Privilege.TEMPORARY;
            case FUNCTION, PROCEDURE ->
                    grant.privilege() == Privilege.EXECUTE;
            default -> false;
        };
    }

    private Set<Grant> expand(
            Connection connection,
            String database,
            List<Grant> grants
    ) throws SQLException {
        Set<Grant> result = new HashSet<>();
        for (Grant grant : grants) {
            if (grant.target().level() == GrantLevel.FUNCTION
                    || grant.target().level() == GrantLevel.PROCEDURE) {
                result.add(resolveRoutine(connection, grant));
            }
            else {
                result.add(grant);
            }
        }
        return result;
    }

    private Set<Grant> expandDefaultPrivileges(
            Connection connection,
            String database,
            Set<DefaultPrivilegeGrant> defaults
    ) throws SQLException {
        Set<Grant> result = new HashSet<>();
        for (DefaultPrivilegeGrant defaultGrant : defaults) {
            DefaultPrivilegeScope scope = defaultGrant.scope();
            if (!database.equals(scope.database())) {
                continue;
            }
            switch (scope.type()) {
                case TABLES -> {
                    for (OwnedObject object : listOwnedObjects(
                            connection, LIST_TABLES, scope.schema())) {
                        if (scope.creator().equals(object.owner())) {
                            result.add(new Grant(
                                    new GrantTarget(
                                            database, scope.schema(),
                                            object.name(), null
                                    ),
                                    defaultGrant.role(),
                                    defaultGrant.privilege()
                            ));
                        }
                    }
                }
                case SEQUENCES -> {
                    for (OwnedObject object : listOwnedObjects(
                            connection, LIST_SEQUENCES, scope.schema())) {
                        if (scope.creator().equals(object.owner())) {
                            result.add(new Grant(
                                    new GrantTarget(
                                            database, scope.schema(), null,
                                            null, object.name()
                                    ),
                                    defaultGrant.role(),
                                    defaultGrant.privilege()
                            ));
                        }
                    }
                }
                case ROUTINES -> {
                    for (RoutineObject object
                            : listOwnedRoutines(connection, scope.schema())) {
                        if (scope.creator().equals(object.owner())) {
                            RoutineSignature signature =
                                    RoutineSignature.parse(
                                            quoteIdentifier(object.name())
                                                    + "(" + object.arguments()
                                                    + ")"
                                    );
                            boolean procedure = "p".equals(object.kind());
                            result.add(new Grant(
                                    new GrantTarget(
                                            database,
                                            scope.schema(),
                                            null,
                                            null,
                                            null,
                                            procedure ? null : signature,
                                            procedure ? signature : null
                                    ),
                                    defaultGrant.role(),
                                    defaultGrant.privilege()
                            ));
                        }
                    }
                }
            }
        }
        return result;
    }

    private List<OwnedObject> listOwnedObjects(
            Connection connection,
            String sql,
            String schema
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                List<OwnedObject> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new OwnedObject(
                            rows.getString(1),
                            rows.getString(2)
                    ));
                }
                return result;
            }
        }
    }

    private List<RoutineObject> listOwnedRoutines(
            Connection connection,
            String schema
    ) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement(LIST_ROUTINES)) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                List<RoutineObject> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new RoutineObject(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4)
                    ));
                }
                return result;
            }
        }
    }

    private record OwnedObject(String name, String owner) {
    }

    private record RoutineObject(
            String name,
            String arguments,
            String kind,
            String owner
    ) {
    }

    private Grant resolveRoutine(Connection connection, Grant grant)
            throws SQLException {
        boolean procedure = grant.target().level() == GrantLevel.PROCEDURE;
        RoutineSignature signature = procedure
                ? grant.target().procedure()
                : grant.target().function();
        String identity = quoteIdentifier(grant.target().schema()) + "."
                + quoteIdentifier(signature.name()) + "("
                + signature.arguments() + ")";
        long oid;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.to_regprocedure(?)::oid"
        )) {
            statement.setString(1, identity);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getObject(1) == null) {
                    throw new SQLException(
                            "PostgreSQL routine does not exist: "
                                    + grant.target().schema() + "."
                                    + signature.display()
                    );
                }
                oid = rows.getLong(1);
            }
        }

        String kindPredicate = procedure
                ? "p.prokind = 'p'"
                : "p.prokind IN ('f', 'a', 'w')";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.proname,
                       pg_catalog.pg_get_function_identity_arguments(p.oid)
                FROM pg_catalog.pg_proc AS p
                WHERE p.oid = ? AND %s
                """.formatted(kindPredicate))) {
            statement.setLong(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException(
                            "Target is not a PostgreSQL "
                                    + (procedure ? "procedure: " : "function: ")
                                    + grant.target().schema() + "."
                                    + signature.display()
                    );
                }
                RoutineSignature canonical = RoutineSignature.parse(
                        quoteIdentifier(rows.getString(1))
                                + "(" + rows.getString(2) + ")"
                );
                return new Grant(
                        new GrantTarget(
                                grant.target().database(),
                                grant.target().schema(),
                                null,
                                null,
                                null,
                                procedure ? null : canonical,
                                procedure ? canonical : null
                        ),
                        grant.role(),
                        grant.privilege()
                );
            }
        }
    }

    private void execute(Connection connection, String operation, Grant grant)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(statement(operation, grant));
        }
    }

    static String statement(String operation, Grant grant) {
        GrantTarget target = grant.target();
        String privilege = grant.privilege().name();
        String relation = target.level() == GrantLevel.TABLE
                || target.level() == GrantLevel.COLUMN
                ? quoteIdentifier(target.schema()) + "."
                + quoteIdentifier(target.table())
                : null;
        String subject = switch (target.level()) {
            case DATABASE -> privilege + " ON DATABASE "
                    + quoteIdentifier(target.database());
            case SCHEMA -> privilege + " ON SCHEMA "
                    + quoteIdentifier(target.schema());
            case TABLE -> privilege + " ON TABLE " + relation;
            case COLUMN -> privilege + " (" + quoteIdentifier(target.column())
                    + ") ON TABLE " + relation;
            case SEQUENCE -> privilege + " ON SEQUENCE "
                    + quoteIdentifier(target.schema()) + "."
                    + quoteIdentifier(target.sequence());
            case FUNCTION -> privilege + " ON FUNCTION "
                    + quoteIdentifier(target.schema()) + "."
                    + quoteIdentifier(target.function().name()) + "("
                    + target.function().arguments() + ")";
            case PROCEDURE -> privilege + " ON PROCEDURE "
                    + quoteIdentifier(target.schema()) + "."
                    + quoteIdentifier(target.procedure().name()) + "("
                    + target.procedure().arguments() + ")";
        };
        return operation + " " + subject
                + ("GRANT".equals(operation) ? " TO " : " FROM ")
                + grantee(grant.role());
    }

    private static String revokeStatement(
            Grant grant,
            boolean grantOptionOnly
    ) {
        String revoke = statement("REVOKE", grant);
        return grantOptionOnly
                ? revoke.replaceFirst("^REVOKE ", "REVOKE GRANT OPTION FOR ")
                : revoke;
    }

    private static String grantee(String role) {
        return "PUBLIC".equals(role) ? "PUBLIC" : quoteIdentifier(role);
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        }
        catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
