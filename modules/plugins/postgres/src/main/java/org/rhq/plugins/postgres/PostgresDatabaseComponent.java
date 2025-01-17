/*
 * RHQ Management Platform
 * Copyright (C) 2005-2013 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA
 */

package org.rhq.plugins.postgres;

import static org.rhq.core.domain.measurement.AvailabilityType.DOWN;
import static org.rhq.core.domain.measurement.AvailabilityType.UP;
import static org.rhq.core.domain.resource.CreateResourceStatus.FAILURE;
import static org.rhq.core.domain.resource.CreateResourceStatus.SUCCESS;
import static org.rhq.plugins.postgres.PostgresDiscoveryComponent.buildConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.core.domain.configuration.Configuration;
import org.rhq.core.domain.configuration.Property;
import org.rhq.core.domain.configuration.PropertyList;
import org.rhq.core.domain.configuration.PropertyMap;
import org.rhq.core.domain.configuration.PropertySimple;
import org.rhq.core.domain.measurement.AvailabilityType;
import org.rhq.core.domain.measurement.MeasurementDataNumeric;
import org.rhq.core.domain.measurement.MeasurementReport;
import org.rhq.core.domain.measurement.MeasurementScheduleRequest;
import org.rhq.core.pluginapi.inventory.CreateChildResourceFacet;
import org.rhq.core.pluginapi.inventory.CreateResourceReport;
import org.rhq.core.pluginapi.inventory.ResourceContext;
import org.rhq.core.pluginapi.measurement.MeasurementFacet;
import org.rhq.core.pluginapi.operation.OperationFacet;
import org.rhq.core.pluginapi.operation.OperationResult;
import org.rhq.plugins.database.ConnectionPoolingSupport;
import org.rhq.plugins.database.DatabaseComponent;
import org.rhq.plugins.database.DatabasePluginUtil;
import org.rhq.plugins.database.PooledConnectionProvider;

public class PostgresDatabaseComponent implements DatabaseComponent<PostgresServerComponent<?>>,
    ConnectionPoolingSupport, MeasurementFacet, CreateChildResourceFacet, OperationFacet {

    private static final Log LOG = LogFactory.getLog(PostgresDatabaseComponent.class);

    private static final String QUERY_DATABASE_SIZE = "SELECT *, pg_database_size(datname) AS size FROM pg_stat_database where datname = ?";

    private ResourceContext<PostgresServerComponent<?>> resourceContext;
    private String databaseName;
    private PostgresServerComponent<?> postgresServerComponent;
    private boolean useOwnJdbcConnections;
    @Deprecated
    private Connection databaseConnection;
    private PostgresPooledConnectionProvider pooledConnectionProvider;

    public void start(ResourceContext<PostgresServerComponent<?>> context) throws Exception {
        this.resourceContext = context;
        databaseName = resourceContext.getPluginConfiguration().getSimple("databaseName").getStringValue();
        postgresServerComponent = resourceContext.getParentResourceComponent();
        useOwnJdbcConnections = !databaseName.equals(postgresServerComponent.getResourceContext()
            .getPluginConfiguration().getSimple("db").getStringValue());
        if (useOwnJdbcConnections) {
            buildDatabaseConnectionIfNeeded();
            pooledConnectionProvider = new PostgresPooledConnectionProvider(createDatabaseSpecificConfig());
        }
    }

    public void stop() {
        this.resourceContext = null;
        databaseName = null;
        postgresServerComponent = null;
        if (useOwnJdbcConnections) {
            DatabasePluginUtil.safeClose(databaseConnection);
            databaseConnection = null;
            pooledConnectionProvider.close();
            pooledConnectionProvider = null;
        }
    }

    @Override
    public boolean supportsConnectionPooling() {
        return true;
    }

    @Override
    public PooledConnectionProvider getPooledConnectionProvider() {
        return useOwnJdbcConnections ? pooledConnectionProvider : postgresServerComponent.getPooledConnectionProvider();
    }

    public Connection getConnection() {
        if (useOwnJdbcConnections) {
            return postgresServerComponent.getConnection();
        } else {
            buildDatabaseConnectionIfNeeded();
            return this.databaseConnection;
        }
    }

    public void removeConnection() {
        try {
            if ((this.databaseConnection != null) && !this.databaseConnection.isClosed()) {
                this.databaseConnection.close();
            }
        } catch (SQLException e) {
            LOG.debug("Could not remove connection", e);
        }
        this.databaseConnection = null;
    }

    private void buildDatabaseConnectionIfNeeded() {
        try {
            if (this.databaseConnection == null || this.databaseConnection.isClosed()) {
                this.databaseConnection = buildConnection(createDatabaseSpecificConfig(), true);
            }
        } catch (SQLException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not build shared connection", e);
            }
        }
    }

    private Configuration createDatabaseSpecificConfig() {
        Configuration config = postgresServerComponent.getResourceContext().getPluginConfiguration();
        config = config.deepCopy();
        config.put(new PropertySimple("db", databaseName));
        if (LOG.isDebugEnabled()) {
            LOG.debug("Getting db specific connection to postgres for [" + databaseName + "] database");
        }
        return config;
    }

    public AvailabilityType getAvailability() {
        if (useOwnJdbcConnections) {
            Connection jdbcConnection = null;
            try {
                jdbcConnection = getPooledConnectionProvider().getPooledConnection();
                return jdbcConnection.isValid(1) ? UP : DOWN;
            } catch (SQLException e) {
                return DOWN;
            } finally {
                DatabasePluginUtil.safeClose(jdbcConnection);
            }
        }
        return postgresServerComponent.getAvailability();
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void getValues(MeasurementReport report, Set<MeasurementScheduleRequest> metrics) {
        Connection jdbcConnection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            jdbcConnection = getPooledConnectionProvider().getPooledConnection();
            statement = jdbcConnection.prepareStatement(QUERY_DATABASE_SIZE);
            statement.setString(1, this.resourceContext.getPluginConfiguration().getSimple("databaseName")
                .getStringValue());
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Result set is empty: " + QUERY_DATABASE_SIZE);
   }
            }
            for (MeasurementScheduleRequest request : metrics) {
                report.addData(new MeasurementDataNumeric(request, resultSet.getDouble(request.getName())));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DatabasePluginUtil.safeClose(jdbcConnection, statement, resultSet);
        }
    }

    public CreateResourceReport createResource(CreateResourceReport report) {
        StringBuilder buf = new StringBuilder();
        Configuration configuration = report.getResourceConfiguration();

        String tableName = configuration.getSimple("tableName").getStringValue();
        String owner = configuration.getSimpleValue("owner", null);
        String tablespace = configuration.getSimpleValue("tablespace", null);
        PropertyList columnList = configuration.getList("columns");

        buf.append("CREATE TABLE ").append(tableName).append("(\n");

        boolean first = true;
        for (Property c : columnList.getList()) {
            if (!first) {
                buf.append(",\n");
            }

            PropertyMap column = (PropertyMap) c;
            String colName = column.getSimple("columnName").getStringValue();
            String colType = column.getSimple("columnType").getStringValue();
            PropertySimple length = column.getSimple("columnLength");
            PropertySimple precision = column.getSimple("columnPrecision");
            PropertySimple colDefault = column.getSimple("columnDefault");
            PropertySimple colNullable = column.getSimple("columnNullable");

            if ((colName != null) && !colName.equals("")) {
                buf.append(colName).append(" ").append(colType);
                if ((length != null) && (length.getIntegerValue() != null)) {
                    buf.append("(" + length.getIntegerValue() + ")");
                }

                if ((precision != null) && (precision.getIntegerValue() != null)) {
                    buf.append("(" + precision.getIntegerValue() + ")");
                }

                if ((colDefault != null) && (colDefault.getStringValue() != null)) {
                    buf.append(" DEFAULT " + colDefault.getStringValue());
                }

                if ((colNullable != null) && (colNullable.getBooleanValue() != null)
                    && colNullable.getBooleanValue().equals(Boolean.FALSE)) {
                    buf.append(" NOT NULL");
                }

                first = false;
            }
        }

        buf.append("\n)");

        String createTableSql = buf.toString();
        LOG.info("Creating table with: " + createTableSql);
        PropertyList constraintList = configuration.getList("constraints");
        if (constraintList != null) {
            for (Property c : constraintList.getList()) {
                PropertyMap constraint = (PropertyMap) c;
                // TODO
            }
        }

        Connection jdbcConnection = null;
        Statement statement = null;
        try {
            jdbcConnection = getPooledConnectionProvider().getPooledConnection();
            statement = jdbcConnection.createStatement();
            statement.executeUpdate(createTableSql);
            report.setStatus(SUCCESS);
            report.setResourceKey(tableName);
            report.setResourceName(tableName);
        } catch (SQLException e) {
            report.setException(e);
            report.setStatus(FAILURE);
        } finally {
            DatabasePluginUtil.safeClose(jdbcConnection, statement, null);
        }

        return report;
    }

    public OperationResult invokeOperation(String name, Configuration parameters) throws InterruptedException,
        Exception {
        if ("resetStatistics".equals(name)) {
            return resetStatistics();
        } else if ("invokeSql".equals(name)) {
            return invokeSql(parameters);
        } else {
            throw new UnsupportedOperationException("Operation [" + name + "] is not supported yet.");
        }
    }

    private OperationResult resetStatistics() {
        Connection jdbcConnection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            jdbcConnection = getPooledConnectionProvider().getPooledConnection();
            statement = jdbcConnection.createStatement();
            resultSet = statement.executeQuery("select * from pg_stat_reset()");
            return null; // does not return results
        } catch (SQLException e) {
            OperationResult result = new OperationResult("Failed to reset statistics");
            result.setErrorMessage(e.getMessage());
            return result;
        } finally {
            DatabasePluginUtil.safeClose(jdbcConnection, statement, resultSet);
        }
    }

    private OperationResult invokeSql(Configuration parameters) throws SQLException {
        Connection jdbcConnection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            jdbcConnection = getPooledConnectionProvider().getPooledConnection();
            statement = jdbcConnection.createStatement();
            String sql = parameters.getSimple("sql").getStringValue();

            OperationResult result = new OperationResult();
            if (parameters.getSimple("type").getStringValue().equals("update")) {
                int updateCount = statement.executeUpdate(sql);
                result.getComplexResults().put(new PropertySimple("result", "Query updated " + updateCount + " rows"));
            } else {
                resultSet = statement.executeQuery(sql);

                ResultSetMetaData md = resultSet.getMetaData();
                StringBuilder buf = new StringBuilder();
                int rowCount = 0;

                buf.append("<table>");
                buf.append("<th>");
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    buf.append("<td>");
                    buf.append(md.getColumnName(i) + " (" + md.getColumnTypeName(i) + ")");
                    buf.append("</td>");
                }
                buf.append("</th>");

                while (resultSet.next()) {
                    rowCount++;
                    buf.append("<tr>");
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        buf.append("<td>");
                        buf.append(resultSet.getString(i));
                        buf.append("</td>");
                    }
                    buf.append("</tr>");
                }

                buf.append("</table>");
                result.getComplexResults().put(new PropertySimple("result", "Query returned " + rowCount + " rows"));
                result.getComplexResults().put(new PropertySimple("contents", buf.toString()));
            }
            return result;
        } catch (SQLException e) {
            OperationResult result = new OperationResult("Failed to invoke SQL");
            result.setErrorMessage(e.getMessage());
            return result;
        } finally {
            DatabasePluginUtil.safeClose(jdbcConnection, statement, resultSet);
        }
    }
}
