/*
 * Copyright 1999–2025 ViaOA (info@viaoa.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.viaoa.datasource.jdbc.delegate;

import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.lang.OAString;

/**
 * Utility for generating vendor-aware DDL statements.
 * <p>
 * Produces {@code CREATE TABLE} scaffolding, index and constraint DDL, table/column
 * alterations, and vendor-specific syntactic variants (e.g., dropping indexes,
 * Access/SQLServer UPDATE styles, Postgres JSON casting compatibility).
 * </p>
 *
 * @see com.viaoa.datasource.jdbc.db.DBMetaData
 */
public class DDLDelegate {

	/**
	 * Returns a SQL statement used to create an empty table.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the name of the table to create
	 * @return the CREATE TABLE SQL statement
	 */
	public static String getCreateTableSQL(DBMetaData dbmd, String tableName) {
		return "CREATE TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket + ";";
	}

	/**
	 * Returns the initial portion of a CREATE TABLE statement.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the name of the table to create
	 * @return the opening CREATE TABLE SQL fragment
	 */
	public static String getBeginCreateTableSQL(DBMetaData dbmd, String tableName) {
		return "CREATE TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket + "(";
	}

	/**
	 * Returns the closing portion of a CREATE TABLE statement.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the closing SQL fragment
	 */
	public static String getEndCreateTableSQL(DBMetaData dbmd) {
		return ");";
	}

	/**
	 * Placeholder method for generating an INSERT-SELECT SQL statement.
	 * <p>
	 * This method currently returns {@code null} and is retained for
	 * potential future use.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param fromName source table name
	 * @param toName destination table name
	 * @return {@code null}
	 */
	public static String getInsertRecordsSQL_HOLD(DBMetaData dbmd, String fromName, String toName) {
		/* was:  Access did not like the "()" around the select
		String s = "INSERT INTO " + dbmd.leftBracket + toName + dbmd.rightBracket + " ";
		s += "(Select * FROM " + dbmd.leftBracket + fromName + dbmd.rightBracket + ");";
		*/
		/***
		 * 2007/11/19 commented out until it is needed // need to use columns ResultSet rs = null; String colsFrom = null; String colsTo =
		 * null; try { rs = connectionPool.databaseMetaData.getColumns(null,null,toName, null); for ( ;rs.next(); ) { String columnName =
		 * (String) rs.getString(4); if (rs.wasNull()) continue; if (colsTo == null) colsTo = ""; else colsTo += ", "; colsTo +=
		 * dbmd.leftBracket + columnName + dbmd.rightBracket; if (colsFrom == null) colsFrom = ""; else colsFrom += ", "; colsFrom +=
		 * dbmd.leftBracket + columnName + dbmd.rightBracket; } } catch (SQLException e) { e.printStackTrace(); System.out.println(""+e); }
		 * finally { if (rs != null) { try { rs.close(); } catch (SQLException e) { } } } String s = "INSERT INTO " + dbmd.leftBracket +
		 * toName + dbmd.rightBracket + " (" + colsTo + ") "; s += "Select "+colsFrom+" FROM " + dbmd.leftBracket + fromName +
		 * dbmd.rightBracket + ";"; return s;
		 */
		return null;
	}

	/**
	 * Returns a SQL statement that inserts records from one table into another
	 * using a SELECT clause.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param fromName source table name
	 * @param toName destination table name
	 * @param columnNames comma-separated list of column names
	 * @return the INSERT-SELECT SQL statement
	 */
	public static String getInsertRecordsSQL(DBMetaData dbmd, String fromName, String toName, String columnNames) {
		/*
		String s = "INSERT INTO " + dbmd.leftBracket + toName + dbmd.rightBracket + " (" + columnNames + ") VALUES ";
		s += "(Select "+columnNames+" FROM " + dbmd.leftBracket + fromName + dbmd.rightBracket + ");";
		*/

		// Access
		// INSERT INTO Client (Id) Select Id from Pet
		String s = "INSERT INTO " + dbmd.leftBracket + toName + dbmd.rightBracket + " (" + columnNames + ") ";
		s += "Select " + columnNames + " FROM " + dbmd.leftBracket + fromName + dbmd.rightBracket + ";";
		return s;
	}

	/**
	 * Returns a SQL statement that inserts records from one table into another
	 * with optional column mapping and filtering.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param fromName source table name
	 * @param toName destination table name
	 * @param fromColumnNames source column names
	 * @param toColumnNames destination column names
	 * @param where optional WHERE clause
	 * @return the INSERT-SELECT SQL statement
	 */
	public static String getInsertRecordsSQL(DBMetaData dbmd, String fromName, String toName, String fromColumnNames, String toColumnNames,
			String where) {
		/*
		String s = "INSERT INTO " + dbmd.leftBracket + toName + dbmd.rightBracket + " (" + toColumnNames + ") VALUES ";
		s += "(Select "+fromColumnNames+" FROM " + dbmd.leftBracket + fromName + dbmd.rightBracket + ");";
		*/

		if (where == null) {
			where = "";
		}
		if (where.length() > 0) {
			where = " WHERE " + where;
		}
		String s = "INSERT INTO " + dbmd.leftBracket + toName + dbmd.rightBracket + " (" + toColumnNames + ") ";
		s += "Select " + fromColumnNames + " FROM " + dbmd.leftBracket + fromName + dbmd.rightBracket + where + ";";
		return s;
	}

	/**
	 * Returns a SQL statement that updates one column using the value of another
	 * column within the same table.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @param fromColumnName source column name
	 * @param toColumnName destination column name
	 * @return the UPDATE SQL statement
	 */
	public static String getUpdateColumnSQL(DBMetaData dbmd, String tableName, String fromColumnName, String toColumnName) {
		String s = "UPDATE " + dbmd.leftBracket + tableName + dbmd.rightBracket + " SET " + toColumnName + " = " + fromColumnName + ";";
		return s;
	}

	/**
	 * Returns a SQL statement that updates a column in one table using values
	 * from another table.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param fromTableName source table name
	 * @param toTableName destination table name
	 * @param fromColumnName source column name
	 * @param toColumnName destination column name
	 * @return the UPDATE SQL statement
	 */
	public static String getUpdateColumnSQL(DBMetaData dbmd, String fromTableName, String toTableName, String fromColumnName,
			String toColumnName) {
		return getUpdateColumnSQL(dbmd, fromTableName, toTableName, fromColumnName, toColumnName, null);
	}

	/**
	 * Returns a SQL statement that updates a column using values from another
	 * table with an optional WHERE clause.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param fromTableName source table name
	 * @param toTableName destination table name
	 * @param fromColumnName source column name
	 * @param toColumnName destination column name
	 * @param whereClause optional WHERE clause
	 * @return the UPDATE SQL statement
	 */
	public static String getUpdateColumnSQL(DBMetaData dbmd, String fromTableName, String toTableName, String fromColumnName,
			String toColumnName, String whereClause) {
		return getUpdateColumnSQL(	dbmd, fromTableName, toTableName, new String[] { fromColumnName }, new String[] { toColumnName },
									whereClause);
	}

	/**
	 * Returns a SQL statement that updates multiple columns using values
	 * from another table.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param fromTableName source table name
	 * @param toTableName destination table name
	 * @param fromColumnNames source column names
	 * @param toColumnNames destination column names
	 * @return the UPDATE SQL statement
	 */
	public static String getUpdateColumnSQL(DBMetaData dbmd, String fromTableName, String toTableName, String[] fromColumnNames,
			String[] toColumnNames) {
		return getUpdateColumnSQL(dbmd, fromTableName, toTableName, fromColumnNames, toColumnNames, null);
	}

	/**
	 * Returns a vendor-specific SQL statement that updates multiple columns
	 * using values from another table.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param fromTableName source table name
	 * @param toTableName destination table name
	 * @param fromColumnNames source column names
	 * @param toColumnNames destination column names
	 * @param whereClause optional WHERE clause
	 * @return the UPDATE SQL statement
	 */
	public static String getUpdateColumnSQL(DBMetaData dbmd, String fromTableName, String toTableName, String[] fromColumnNames,
			String[] toColumnNames, String whereClause) {
		String sql = "";
		switch (dbmd.databaseType) {
		case DBMetaData.ACCESS:
			// update Appointment, Breed  set Appointment.UserId = Breed.Name WHERE Appointment.ID = Breed.ID
			sql = "UPDATE " + dbmd.leftBracket + fromTableName + dbmd.rightBracket + ", " + dbmd.leftBracket + toTableName
					+ dbmd.rightBracket;
			sql += " SET ";
			for (int i = 0; i < fromColumnNames.length; i++) {
				if (i > 0) {
					sql += ", ";
				}
				sql += dbmd.leftBracket + toTableName + dbmd.rightBracket + "." + toColumnNames[i] + " = ";
				sql += dbmd.leftBracket + fromTableName + dbmd.rightBracket + "." + fromColumnNames[i];
			}
			if (whereClause != null && whereClause.length() > 0) {
				sql += " WHERE " + whereClause;
			}
			break;
		case DBMetaData.SQLSERVER:
			// this is for SQL Server, not sure about others.
			sql = "UPDATE " + dbmd.leftBracket + toTableName + dbmd.rightBracket;
			sql += " SET ";
			for (int i = 0; i < fromColumnNames.length; i++) {
				if (i > 0) {
					sql += ", ";
				}
				sql += dbmd.leftBracket + toTableName + dbmd.rightBracket + "." + toColumnNames[i] + " = ";
				sql += dbmd.leftBracket + fromTableName + dbmd.rightBracket + "." + fromColumnNames[i];
			}
			// sql += " FROM " + dbmd.leftBracket+fromTableName+dbmd.rightBracket;

			if (whereClause != null && whereClause.length() > 0) {
				// sql += ", " + dbmd.leftBracket+toTableName+dbmd.rightBracket;
				sql += " WHERE " + whereClause;
			}
			sql += ";";
			break;
		case DBMetaData.DERBY:
		case DBMetaData.MYSQL:
		case DBMetaData.ORACLE:
		case DBMetaData.POSTGRES:
		case DBMetaData.OTHER:
		default:
			// this is for SQL Server, not sure about others.
			sql = "UPDATE " + dbmd.leftBracket + toTableName + dbmd.rightBracket;
			sql += " SET ";
			for (int i = 0; i < fromColumnNames.length; i++) {
				if (i > 0) {
					sql += ", ";
				}
				sql += toColumnNames[i] + " = ";
				sql += fromColumnNames[i];
			}

			if (whereClause != null && whereClause.length() > 0) {
				// sql += ", " + dbmd.leftBracket+toTableName+dbmd.rightBracket;
				sql += " WHERE " + whereClause;
			}
			sql += ";";
			break;
		}

		return sql;
	}

	/**
	 * Returns a SQL statement that drops a primary key constraint.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @param pkName the primary key constraint name
	 * @return the ALTER TABLE DROP CONSTRAINT SQL statement
	 */
	public static String getDropPkeyConstraintSQL(DBMetaData dbmd, String tableName, String pkName) {
		String s = "ALTER TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket + " DROP Constraint " + pkName + ";";
		return s;
	}

	/**
	 * Returns a SQL statement that adds a primary key constraint.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @param constraintName the primary key constraint name
	 * @param pkeys comma-separated list of primary key columns
	 * @return the ALTER TABLE ADD CONSTRAINT SQL statement
	 */
	public static String getAddPkeyConstraintSQL(DBMetaData dbmd, String tableName, String constraintName, String pkeys) {
		String s = "ALTER TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket;
		s += " Add Constraint " + constraintName;
		s += " PRIMARY KEY (" + pkeys + ");";
		return s;
	}

	/**
	 * Returns a SQL statement that drops an index using vendor-specific syntax.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @param indexName the index name
	 * @return the DROP INDEX SQL statement
	 */
	public static String getDropIndexSQL(DBMetaData dbmd, String tableName, String indexName) {
		String s;
		if (dbmd.databaseType == DBMetaData.SQLSERVER) {
			s = "DROP INDEX " + dbmd.leftBracket + tableName + dbmd.rightBracket + "." + indexName + ";";
		} else if (dbmd.databaseType == DBMetaData.DERBY) {
			s = "DROP INDEX " + indexName + ";";
		} else if (dbmd.databaseType == DBMetaData.POSTGRES) {
			s = "DROP INDEX " + indexName + ";";
		} else if (dbmd.databaseType == DBMetaData.ORACLE) {
			s = "DROP INDEX " + indexName + ";";
		} else if (dbmd.databaseType == DBMetaData.MYSQL) {
			s = "ALTER TABLE " + tableName + ". DROP INDEX " + indexName + ";";
		} else {
			s = "DROP INDEX " + indexName + " ON " + dbmd.leftBracket + tableName + dbmd.rightBracket + ";";
		}
		return s;
	}

	/**
	 * Returns a SQL statement that drops a table.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @return the DROP TABLE SQL statement
	 */
	public static String getDropTableSQL(DBMetaData dbmd, String tableName) {
		String s = "DROP TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket + ";";
		return s;
	}

	/**
	 * Returns a SQL statement that creates a non-unique index.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @param indexName the index name
	 * @param columnNames comma-separated column names
	 * @return the CREATE INDEX SQL statement
	 */
	public static String getCreateIndexSQL(DBMetaData dbmd, String tableName, String indexName, String columnNames) {
		String s = "CREATE INDEX " + indexName + " ON " + dbmd.leftBracket + tableName + dbmd.rightBracket + " (" + columnNames + ");";
		return s;
	}

	/**
	 * Returns a SQL statement that creates a unique index.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @param indexName the index name
	 * @param columnName the indexed column name
	 * @return the CREATE UNIQUE INDEX SQL statement
	 */
	public static String getCreateUniqueIndexSQL(DBMetaData dbmd, String tableName, String indexName, String columnName) {
		String s = "CREATE UNIQUE INDEX " + indexName + " ON " + dbmd.leftBracket + tableName + dbmd.rightBracket + " (" + columnName
				+ ");";
		return s;
	}

	/**
	 * Returns a SQL statement that adds a new column to an existing table.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @param columnName the column name
	 * @param type the column SQL type definition
	 * @return the ALTER TABLE ADD COLUMN SQL statement
	 */
	public static String getAlterAddColumnSQL(DBMetaData dbmd, String tableName, String columnName, String type) {
		String s = " COLUMN";
		switch (dbmd.databaseType) {
		case DBMetaData.ACCESS:
			break;
		case DBMetaData.MYSQL:
			break;
		case DBMetaData.ORACLE:
			break;
		case DBMetaData.SQLSERVER:
			s = "";
			break;
		case DBMetaData.DERBY:
			break;
		}
		String s2 = dbmd.leftBracket + columnName + dbmd.rightBracket + " " + type;
		s = "ALTER TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket + " ADD" + s + " " + s2 + ";";
		return s;
	}

	/**
	 * Returns a SQL fragment used to define a column within a CREATE TABLE statement.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param columnName the column name
	 * @param type the column SQL type definition
	 * @return the column definition SQL fragment
	 */
	public static String getAddColumnSQL(DBMetaData dbmd, String columnName, String type) {
		String s = dbmd.leftBracket + columnName + dbmd.rightBracket + " " + type;
		return s;
	}

	/**
	 * Returns a SQL fragment used to define a column within a CREATE TABLE
	 * statement, including additional parameters.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param columnName the column name
	 * @param type the column SQL type definition
	 * @param params additional column parameters such as constraints
	 * @return the column definition SQL fragment
	 */
	public static String getAddColumnSQL(DBMetaData dbmd, String columnName, String type, String params) {
		String s = dbmd.leftBracket + columnName + dbmd.rightBracket + " " + type;
		if (params != null && params.length() > 0) {
			s += " " + params;
		}
		return s;
	}

	/**
	 * Returns a SQL statement that drops a column from a table.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @param columnName the column name
	 * @return the ALTER TABLE DROP COLUMN SQL statement
	 */
	public static String getDropColumnSQL(DBMetaData dbmd, String tableName, String columnName) {
		String s = "ALTER TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket + " DROP COLUMN " + dbmd.leftBracket + columnName
				+ dbmd.rightBracket + ";";
		return s;
	}

	/**
	 * Returns a SQL statement that alters the data type of an existing column.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table name
	 * @param columnName the column name
	 * @param newType the new SQL type definition
	 * @return the ALTER TABLE ALTER COLUMN SQL statement
	 */
	public static String getAlterColumnSQL(DBMetaData dbmd, String tableName, String columnName, String newType) {
		String sql = null;
		switch (dbmd.databaseType) {
		case DBMetaData.ORACLE: // ok 2007/08/31, not tested
		case DBMetaData.MYSQL: // ok 2007/08/31, not tested
			sql = "ALTER TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket + " MODIFY COLUMN " + dbmd.leftBracket + columnName
					+ dbmd.rightBracket + " " + newType + ";";
			break;
		case DBMetaData.SQLSERVER: // ok 2007/08/31, not tested
		case DBMetaData.ACCESS: // this is correct for Access
			sql = "ALTER TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket + " ALTER COLUMN " + dbmd.leftBracket + columnName
					+ dbmd.rightBracket + " " + newType + ";";
			break;
		case DBMetaData.DERBY: // ok 2007/08/31 tested, note: does not work if suffixed with ';'
			sql = "ALTER TABLE " + dbmd.leftBracket + tableName + dbmd.rightBracket + " ALTER COLUMN " + dbmd.leftBracket + columnName
					+ dbmd.rightBracket + " SET DATA TYPE " + newType;
			break;
		}

		return sql;
	}

	/**
	 * Returns the SQL type definition used to store binary large objects.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param maxLen maximum length of the blob
	 * @return the SQL type definition for binary data
	 */
	public static String getBlobType(DBMetaData dbmd, int maxLen) {
		String sqlType = "BLOB";
		switch (dbmd.databaseType) {
		case DBMetaData.SQLSERVER:
			// 20130112
			sqlType = "varbinary(MAX)";
			break;
		case DBMetaData.POSTGRES:
			// 20190617
			sqlType = "BYTEA";
			break;
		}

		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store Unicode variable-length text.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param maxLen maximum length of the text
	 * @return the SQL type definition for Unicode text
	 */
	public static String getUnicodeType(DBMetaData dbmd, int maxLen) {
		String sqlType = "VARCHAR(" + maxLen + ")";
		switch (dbmd.databaseType) {
		case DBMetaData.ACCESS:
			// todo: need to get correct unicode type for this DB
			break;
		case DBMetaData.MYSQL:
			// todo: need to get correct unicode type for this DB
			break;
		case DBMetaData.ORACLE:
			// todo: need to get correct unicode type for this DB
			break;
		case DBMetaData.SQLSERVER:
			sqlType = "NVARCHAR(" + maxLen + ")";
			break;
		case DBMetaData.DERBY:
			// todo: need to get correct unicode type for this DB
			break;
		case DBMetaData.POSTGRES:
			sqlType = "VARCHAR(" + maxLen + ")";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store Unicode fixed-length text.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param maxLen maximum length of the text
	 * @return the SQL type definition for Unicode character data
	 */
	public static String getUnicodeCharType(DBMetaData dbmd, int maxLen) {
		String sqlType = "char(" + maxLen + ")";
		switch (dbmd.databaseType) {
		case DBMetaData.ACCESS:
			// todo: need to get correct unicode type for this DB
			break;
		case DBMetaData.MYSQL:
			// todo: need to get correct unicode type for this DB
			break;
		case DBMetaData.ORACLE:
			// todo: need to get correct unicode type for this DB
			break;
		case DBMetaData.SQLSERVER:
			sqlType = "NCHAR(" + maxLen + ")";
			break;
		case DBMetaData.DERBY:
			// todo: need to get correct unicode type for this DB
			break;
		case DBMetaData.POSTGRES:
			sqlType = "CHAR(" + maxLen + ")";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store long Unicode text values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param maxLen maximum length of the text
	 * @return the SQL type definition for long Unicode text
	 */
	public static String getLongUnicodeType(DBMetaData dbmd, int maxLen) {
		String sqlType = "";
		switch (dbmd.databaseType) {
		case DBMetaData.ACCESS:
			// todo: need to get correct unicode type for this DB
			sqlType = "memo";
			break;
		case DBMetaData.MYSQL:
			// todo: need to get correct unicode type for this DB
			sqlType = "LONGTEXT";
			break;
		case DBMetaData.ORACLE:
			// todo: need to get correct unicode type for this DB
			sqlType = "long";
			break;
		case DBMetaData.SQLSERVER:
			sqlType = "NVARCHAR(MAX)";
			break;
		case DBMetaData.DERBY:
			// todo: need to get correct unicode type for this DB
			sqlType = "CLOB"; // "CLOB("+maxLen+")";
			break;
		case DBMetaData.POSTGRES:
			sqlType = "TEXT";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store variable-length character data.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param maxLen maximum length of the text
	 * @return the SQL type definition for string data
	 */
	public static String getStringType(DBMetaData dbmd, int maxLen) {
		String sqlType = "VARCHAR(" + maxLen + ")";
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store long non-Unicode text values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param maxLen maximum length of the text
	 * @return the SQL type definition for long text
	 */
	public static String getLongTextType(DBMetaData dbmd, int maxLen) {
		String sqlType = "";
		switch (dbmd.databaseType) {
		case DBMetaData.ACCESS:
			sqlType = "memo";
			break;
		case DBMetaData.MYSQL:
			sqlType = "LONGTEXT";
			break;
		case DBMetaData.ORACLE:
			sqlType = "long";
			break;
		case DBMetaData.SQLSERVER:
			// 20130112
			sqlType = "varchar(MAX)";
			//was: sqlType = "text";
			break;
		case DBMetaData.DERBY:
			sqlType = "CLOB"; // "CLOB("+maxLen+")";
			break;
		case DBMetaData.POSTGRES:
			sqlType = "TEXT";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store boolean values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for boolean data
	 */
	public static String getBooleanType(DBMetaData dbmd) {
		String sqlType = "bit";
		switch (dbmd.databaseType) {
		case DBMetaData.ACCESS:
			sqlType = "bit";
			break;
		case DBMetaData.ORACLE:
			sqlType = "char";
			break;
		case DBMetaData.SQLSERVER:
			sqlType = "bit";
			break;
		case DBMetaData.MYSQL:
			sqlType = "BIT";
			break;
		case DBMetaData.DERBY:
			sqlType = "smallint";
			break;
		case DBMetaData.POSTGRES:
			sqlType = "boolean";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store integer values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for integer data
	 */
	public static String getIntType(DBMetaData dbmd) {
		String sqlType = "int";
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store small integer values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for small integer data
	 */
	public static String getSmallIntType(DBMetaData dbmd) {
		String sqlType = "smallint";
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store long integer values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for long integer data
	 */
	public static String getLongType(DBMetaData dbmd) {
		String sqlType = "long";
		switch (dbmd.databaseType) {
		case DBMetaData.SQLSERVER:
		case DBMetaData.POSTGRES:
		case DBMetaData.DERBY:
			sqlType = "BIGINT";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store floating-point values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param decimalLength decimal precision
	 * @return the SQL type definition for floating-point data
	 */
	public static String getFloatType(DBMetaData dbmd, int decimalLength) {
		String sqlType = "float";
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store double-precision values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param decimalLen decimal precision
	 * @return the SQL type definition for double data
	 */
	public static String getDoubleType(DBMetaData dbmd, int decimalLen) {
		String sqlType = "float";
		switch (dbmd.databaseType) {
		case DBMetaData.SQLSERVER:
			sqlType = "REAL";
			break;
		case DBMetaData.DERBY:
			sqlType = "DOUBLE";
			break;
		case DBMetaData.POSTGRES:
			sqlType = "real";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store date-only values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for date data
	 */
	public static String getDateType(DBMetaData dbmd) {
		String sqlType = "datetime";
		switch (dbmd.databaseType) {
		case DBMetaData.SQLSERVER:
		case DBMetaData.POSTGRES:
		case DBMetaData.MYSQL:
		case DBMetaData.ORACLE:
		case DBMetaData.DERBY:
			sqlType = "DATE";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store date and time values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for date-time data
	 */
	public static String getDateTimeType(DBMetaData dbmd) {
		String sqlType = "DATETIME"; // ??????
		switch (dbmd.databaseType) {
		case DBMetaData.POSTGRES:
			sqlType = "TIMESTAMP WITHOUT TIME ZONE";
			break;
		case DBMetaData.MYSQL:
			sqlType = "TIMESTAMP"; // ?????? DATETIME
			break;
		case DBMetaData.ORACLE:
			sqlType = "DATE";
			break;
		case DBMetaData.DERBY:
			sqlType = "TIMESTAMP"; // VALUES TIMESTAMP('1962-09-23 03:23:34.234')
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store date and time values
	 * with timezone information.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for date-time with timezone
	 */
	public static String getDateTimeTzType(DBMetaData dbmd) {
		String sqlType = "DATETIME";
		switch (dbmd.databaseType) {
		case DBMetaData.POSTGRES:
			sqlType = "TIMESTAMP WITH TIME ZONE";
			break;
		case DBMetaData.MYSQL:
			sqlType = "TIMESTAMPTZ"; // ?????? DATETIME
			break;
		case DBMetaData.ORACLE:
			sqlType = "DATETZ";
			break;
		case DBMetaData.DERBY:
			sqlType = "TIMESTAMPTZ"; // VALUES TIMESTAMP('1962-09-23 03:23:34.234')
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store timestamp values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for timestamp data
	 */
	public static String getTimestampType(DBMetaData dbmd) {
		String sqlType = "TIMESTAMP";
		switch (dbmd.databaseType) {
		case DBMetaData.MYSQL:
			sqlType = "TIMESTAMP";
			break;
		case DBMetaData.ORACLE: // see: http://docs.oracle.com/javase/1.5.0/docs/guide/jdbc/getstart/mapping.html
			sqlType = "DATET";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store timestamp values
	 * with timezone information.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for timestamp with timezone
	 */
	public static String getTimestampTzType(DBMetaData dbmd) {
		String sqlType = "TIMESTAMPTZ";
		switch (dbmd.databaseType) {
		case DBMetaData.MYSQL:
			sqlType = "TIMESTAMPTZ";
			break;
		case DBMetaData.ORACLE: // see: http://docs.oracle.com/javase/1.5.0/docs/guide/jdbc/getstart/mapping.html
			sqlType = "DATETZ?";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store timestamp values
	 * with timezone information.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for timestamp with timezone
	 */
	public static String getCurrencyType(DBMetaData dbmd, int decimalLen) {
		String sqlType = "float";
		switch (dbmd.databaseType) {
		case DBMetaData.ACCESS:
			sqlType = "currency";
			// numeric(8, 2)
			break;
		case DBMetaData.ORACLE:
			sqlType = "NUMBER(8," + decimalLen + ")";
			break;
		case DBMetaData.SQLSERVER:
			sqlType = "money";
			break;
		case DBMetaData.MYSQL:
			sqlType = "DECIMAL(8," + decimalLen + ")";
			break;
		case DBMetaData.DERBY:
			sqlType = "DECIMAL(16," + decimalLen + ")";
			break;
		case DBMetaData.POSTGRES:
			sqlType = "money";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store time-only values.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @return the SQL type definition for time data
	 */
	public static String getTimeType(DBMetaData dbmd) {
		String sqlType = "datetime";
		switch (dbmd.databaseType) {
		case DBMetaData.MYSQL:
			sqlType = "TIME";
			break;
		case DBMetaData.ORACLE:
			sqlType = "DATE";
			break;
		case DBMetaData.DERBY:
			sqlType = "TIME";
			break;
		case DBMetaData.POSTGRES:
			sqlType = "TIME";
			break;
		}
		return sqlType;
	}

	/**
	 * Returns the SQL type definition used to store numeric values with
	 * precision and scale.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param maxLen total number of digits
	 * @param decimalLength number of decimal places
	 * @return the SQL type definition for numeric data
	 */
	public static String getNumberType(DBMetaData dbmd, int maxLen, int decimalLength) {
		String sqlType = "numeric(" + maxLen + "," + decimalLength + ")";
		if (dbmd.databaseType == DBMetaData.DERBY) {
			sqlType = "decimal(" + maxLen + "," + decimalLength + ")";
		}
		return sqlType;
	}

	/**
	 * Returns a SQL statement that adds a foreign key constraint.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param fromTable the table containing the foreign key
	 * @param toTable the referenced table
	 * @param constraintName the foreign key constraint name
	 * @param fromColumns the foreign key column names
	 * @param toColumns the referenced column names
	 * @return the ALTER TABLE ADD FOREIGN KEY SQL statement
	 */
	public static String getAddForeignKeySQL(DBMetaData dbmd, String fromTable, String toTable, String constraintName, String fromColumns,
			String toColumns) {
		String s = "";
		s += ("ALTER TABLE " + fromTable + " ADD");
		s += (" CONSTRAINT " + constraintName + " FOREIGN KEY (" + fromColumns + ")");
		s += (" REFERENCES " + toTable + " (" + toColumns + ");");
		return s;
	}

	/**
	 * Returns a SQL statement that drops a foreign key constraint.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param tableName the table containing the constraint
	 * @param constraintName the foreign key constraint name
	 * @return the ALTER TABLE DROP FOREIGN KEY SQL statement
	 */
	public static String getDropForeignKeySQL(DBMetaData dbmd, String tableName, String constraintName) {
		String s = "";
		s += "ALTER TABLE " + tableName + " DROP";
		s += " CONSTRAINT " + constraintName + ";";
		return s;
	}

	/**
	 * Returns a SQL DEFAULT clause derived from an OA-style default value.
	 * <p>
	 * Recognizes {@code new OADate()} and {@code new OADateTime()} expressions
	 * and converts them to database-specific current date or timestamp literals.
	 *
	 * @param dbmd database metadata describing vendor-specific syntax
	 * @param type the column SQL type
	 * @param defaultValue the OA default value expression
	 * @return the SQL DEFAULT clause, or {@code null} if no default applies
	 */
	public static String getDefaultValue(DBMetaData dbmd, String type, String defaultValue) {
		if (OAString.isEmpty(defaultValue)) {
			return null;
		}
		if (defaultValue.startsWith("new OADate()")) {
			defaultValue = "CURRENT_DATE";
		} else if (defaultValue.startsWith("new OADateTime()")) {
			defaultValue = "CURRENT_TIMESTAMP";
		} else {
			defaultValue += " ???? needs to be converted ????";
		}
		defaultValue = "DEFAULT " + defaultValue;

		return defaultValue;
	}
}
