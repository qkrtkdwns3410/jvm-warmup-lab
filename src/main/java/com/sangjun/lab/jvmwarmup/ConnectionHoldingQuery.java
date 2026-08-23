package com.sangjun.lab.jvmwarmup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import javax.sql.DataSource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

/**
 * The simulate profile needs deterministic checkout duration. It executes a
 * real SELECT, then deliberately keeps that physical Hikari connection open
 * while the first-use gate runs. This is the controlled stand-in for the
 * production incident's connection-held JVM work.
 */
@Component
public class ConnectionHoldingQuery {
    private final DataSource dataSource;
    private final ColdPathGate gate;

    public ConnectionHoldingQuery(DataSource dataSource, ColdPathGate gate) {
        this.dataSource = dataSource;
        this.gate = gate;
    }

    public void selectThenHold(long id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select id from product where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("product not found: " + id);
            }
            gate.waitForFirstUseWhileConnectionIsBorrowed();
        } catch (SQLTransientConnectionException exception) {
            throw new CannotGetJdbcConnectionException("Hikari connection unavailable", exception);
        } catch (SQLException exception) {
            throw new IllegalStateException("lab query failed", exception);
        }
    }
}
