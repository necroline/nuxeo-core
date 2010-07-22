/*
 * (C) Copyright 2008-2009 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */

package org.nuxeo.ecm.core.storage.sql.jdbc.dialect;

import java.io.Serializable;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.Binary;
import org.nuxeo.ecm.core.storage.sql.BinaryManager;
import org.nuxeo.ecm.core.storage.sql.ColumnType;
import org.nuxeo.ecm.core.storage.sql.Model;
import org.nuxeo.ecm.core.storage.sql.RepositoryDescriptor;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Column;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Database;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Table;

/**
 * Derby-specific dialect.
 *
 * @author Florent Guillaume
 */
public class DialectDerby extends Dialect {

    public DialectDerby(DatabaseMetaData metadata, BinaryManager binaryManager,
            RepositoryDescriptor repositoryDescriptor) throws StorageException {
        super(metadata, binaryManager, repositoryDescriptor);
    }

    @Override
    public JDBCInfo getJDBCTypeAndString(ColumnType type) {
        switch (type) {
        case VARCHAR:
            return jdbcInfo("VARCHAR(32672)", Types.VARCHAR);
        case CLOB:
            return jdbcInfo("CLOB", Types.CLOB);
        case BOOLEAN:
            return jdbcInfo("SMALLINT", Types.SMALLINT);
        case LONG:
            return jdbcInfo("BIGINT", Types.BIGINT);
        case DOUBLE:
            return jdbcInfo("DOUBLE", Types.DOUBLE);
        case TIMESTAMP:
            return jdbcInfo("TIMESTAMP", Types.TIMESTAMP);
        case BLOBID:
            return jdbcInfo("VARCHAR(40)", Types.VARCHAR);
            // -----
        case NODEID:
        case NODEIDFK:
        case NODEIDFKNP:
        case NODEIDFKMUL:
        case NODEIDFKNULL:
        case NODEVAL:
            return jdbcInfo("VARCHAR(36)", Types.VARCHAR);
        case SYSNAME:
            return jdbcInfo("VARCHAR(250)", Types.VARCHAR);
        case TINYINT:
            return jdbcInfo("SMALLINT", Types.TINYINT);
        case INTEGER:
            return jdbcInfo("INTEGER", Types.INTEGER);
        case FTINDEXED:
            return jdbcInfo("CLOB", Types.CLOB);
        case FTSTORED:
            return jdbcInfo("CLOB", Types.CLOB);
        case CLUSTERNODE:
            return jdbcInfo("INTEGER", Types.INTEGER);
        case CLUSTERFRAGS:
            return jdbcInfo("VARCHAR(4000)", Types.VARCHAR);
        }
        throw new AssertionError(type);
    }

    @Override
    public boolean isAllowedConversion(int expected, int actual,
            String actualName, int actualSize) {
        // CLOB vs VARCHAR compatibility
        if (expected == Types.VARCHAR && actual == Types.CLOB) {
            return true;
        }
        if (expected == Types.CLOB && actual == Types.VARCHAR) {
            return true;
        }
        // INTEGER vs BIGINT compatibility
        if (expected == Types.BIGINT && actual == Types.INTEGER) {
            return true;
        }
        if (expected == Types.INTEGER && actual == Types.BIGINT) {
            return true;
        }
        return false;
    }

    @Override
    public void setToPreparedStatement(PreparedStatement ps, int index,
            Serializable value, Column column) throws SQLException {
        switch (column.getJdbcType()) {
        case Types.VARCHAR:
        case Types.CLOB:
            String v;
            if (column.getType() == ColumnType.BLOBID) {
                v = ((Binary) value).getDigest();
            } else {
                v = (String) value;
            }
            ps.setString(index, v);
            break;
        case Types.SMALLINT:
            ps.setBoolean(index, ((Boolean) value).booleanValue());
            return;
        case Types.INTEGER:
        case Types.BIGINT:
            ps.setLong(index, ((Long) value).longValue());
            return;
        case Types.DOUBLE:
            ps.setDouble(index, ((Double) value).doubleValue());
            return;
        case Types.TIMESTAMP:
            Calendar cal = (Calendar) value;
            Timestamp ts = new Timestamp(cal.getTimeInMillis());
            ps.setTimestamp(index, ts, cal); // cal passed for timezone
            return;
        default:
            throw new SQLException("Unhandled JDBC type: "
                    + column.getJdbcType());
        }
    }

    @Override
    @SuppressWarnings("boxing")
    public Serializable getFromResultSet(ResultSet rs, int index, Column column)
            throws SQLException {
        switch (column.getJdbcType()) {
        case Types.VARCHAR:
        case Types.CLOB:
            String string = rs.getString(index);
            if (column.getType() == ColumnType.BLOBID && string != null) {
                return getBinaryManager().getBinary(string);
            } else {
                return string;
            }
        case Types.SMALLINT:
            return rs.getBoolean(index);
        case Types.INTEGER:
        case Types.BIGINT:
            return rs.getLong(index);
        case Types.DOUBLE:
            return rs.getDouble(index);
        case Types.TIMESTAMP:
            Timestamp ts = rs.getTimestamp(index);
            if (ts == null) {
                return null;
            } else {
                Serializable cal = new GregorianCalendar(); // XXX timezone
                ((Calendar) cal).setTimeInMillis(ts.getTime());
                return cal;
            }
        }
        throw new SQLException("Unhandled JDBC type: " + column.getJdbcType());
    }

    @Override
    public int getFulltextIndexedColumns() {
        return 0;
    }

    @Override
    public boolean getMaterializeFulltextSyntheticColumn() {
        return true;
    }

    @Override
    public String getCreateFulltextIndexSql(String indexName,
            String quotedIndexName, Table table, List<Column> columns,
            Model model) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getDialectFulltextQuery(String query) {
        return query; // TODO
    }

    // SELECT ..., 1 as nxscore
    // FROM ... LEFT JOIN NXFT_SEARCH('default', ?) nxfttbl
    // .................. ON hierarchy.id = nxfttbl.KEY
    // WHERE ... AND nxfttbl.KEY IS NOT NULL
    // ORDER BY nxscore DESC
    @Override
    public FulltextMatchInfo getFulltextScoredMatchInfo(String fulltextQuery,
            String indexName, int nthMatch, Column mainColumn, Model model,
            Database database) {
        // TODO multiple indexes
        Column ftColumn = database.getTable(model.FULLTEXT_TABLE_NAME).getColumn(
                model.FULLTEXT_FULLTEXT_KEY);
        String qname = ftColumn.getFullQuotedName();
        if (ftColumn.getJdbcType() == Types.CLOB) {
            String colFmt = getClobCast(false);
            if (colFmt != null) {
                qname = String.format(colFmt, qname, Integer.valueOf(255));
            }
        }
        FulltextMatchInfo info = new FulltextMatchInfo();
        info.whereExpr = String.format("NX_CONTAINS(%s, ?) = 1", qname);
        info.whereExprParam = fulltextQuery;
        // TODO score
        return info;
    }

    @Override
    public boolean supportsUpdateFrom() {
        return false;
    }

    @Override
    public boolean doesUpdateFromRepeatSelf() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean needsAliasForDerivedTable() {
        return true;
    }

    @Override
    public String getClobCast(boolean inOrderBy) {
        return "CAST(%s AS VARCHAR(%d))";
    }

    @Override
    public String getSecurityCheckSql(String idColumnName) {
        return String.format("NX_ACCESS_ALLOWED(%s, ?, ?) = 1", idColumnName);
    }

    @Override
    public String getInTreeSql(String idColumnName) {
        return String.format("NX_IN_TREE(%s, ?) = 1", idColumnName);
    }

    private final String derbyFunctions = "org.nuxeo.ecm.core.storage.sql.db.DerbyFunctions";

    @Override
    public String getSQLStatementsFilename() {
        return "nuxeovcs/derby.sql.txt";
    }

    @Override
    public String getTestSQLStatementsFilename() {
        return "nuxeovcs/derby.test.sql.txt";
    }

    @Override
    public Map<String, Serializable> getSQLStatementsProperties(Model model,
            Database database) {
        Map<String, Serializable> properties = new HashMap<String, Serializable>();
        properties.put("idType", "VARCHAR(36)");
        properties.put("fulltextEnabled", Boolean.valueOf(!fulltextDisabled));
        properties.put("derbyFunctions", derbyFunctions);
        properties.put(SQLStatement.DIALECT_WITH_NO_SEMICOLON, Boolean.TRUE);
        return properties;
    }

    @Override
    public boolean supportsPaging() {
        return true;
    }

    @Override
    public String getPagingClause(long limit, long offset) {
        return String.format("OFFSET %d ROWS FETCH FIRST %d ROWS ONLY", offset,
                limit); // available from 10.5
    }

}
