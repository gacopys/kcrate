package com.example.crateproxy;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Phase 1 passthrough stub.
 * Per D-01: no SQL rewriting -- all calls forwarded directly to pgJDBC.
 * Per D-02: Keycloak will crash on BEGIN; that is acceptable for Phase 1.
 */
public class CrateProxyDriver implements Driver {

    private static final Driver REAL;

    static {
        try {
            // Load pgJDBC so its Driver instance exists, then immediately deregister it.
            // This prevents pgJDBC from winning jdbc:postgresql: URL dispatch before us.
            Class.forName("org.postgresql.Driver");
            Driver pgDriver = DriverManager.getDriver("jdbc:postgresql://localhost/");
            DriverManager.deregisterDriver(pgDriver);
            REAL = pgDriver;

            DriverManager.registerDriver(new CrateProxyDriver());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return REAL.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return REAL.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() { return 1; }

    @Override
    public int getMinorVersion() { return 0; }

    @Override
    public boolean jdbcCompliant() { return false; }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("com.example.crateproxy");
    }
}
