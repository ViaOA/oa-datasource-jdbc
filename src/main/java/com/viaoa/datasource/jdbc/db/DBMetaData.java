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
package com.viaoa.datasource.jdbc.db;

import com.viaoa.annotation.OAClass;
import com.viaoa.datasource.jdbc.delegate.DBMetaDataDelegate;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.object.OAObject;

// DBMetaData.ORACLE:  http://docs.oracle.com/javase/1.5.0/docs/guide/jdbc/getstart/mapping.html
// SqlServer: http://msdn.microsoft.com/en-us/library/ms191530.aspx

/**
 * Holds database-specific configuration, keywords, and behavior flags for JDBC connections.
 * <p>
 * {@code DBMetaData} encapsulates differences across major SQL vendors
 * (Oracle, SQL Server, MySQL, PostgreSQL, DB2, etc.) and provides a
 * centralized configuration object used by {@link com.viaoa.datasource.jdbc.OADataSourceJDBC}.
 * </p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Track driver, URL, and credential information.</li>
 *   <li>Define dialect flags such as quoting style, boolean syntax, and join behavior.</li>
 *   <li>Notify delegates on database type changes for re-initialization.</li>
 * </ul>
 *
 * <h2>Design Notes</h2>
 * Each OA JDBC DataSource uses a single {@code DBMetaData} instance
 * to standardize query generation and type conversion across sessions.
 *
 * @see com.viaoa.datasource.jdbc.delegate.DBMetaDataDelegate
 * @see com.viaoa.datasource.jdbc.OADataSourceJDBC
 */
@OAClass(useDataSource = false, localOnly = false)
public class DBMetaData extends OAObject {
	static final long serialVersionUID = 1L;

	/**
	 * Constant identifying an unspecified or generic SQL database type.
	 */
	public final static int OTHER = 0;

	/**
	 * Constant identifying the Apache Derby SQL database type.
	 */
	public final static int DERBY = 1;
	
	/**
	 * Constant identifying the Microsoft SQL Server database type.
	 */
	public final static int SQLSERVER = 2;
	
	/**
	 * Constant identifying the Oracle SQL database type.
	 */
	public final static int ORACLE = 3;
	
	/**
	 * Constant identifying Microsoft Access. Deprecated because the JDBC ODBC
	 * bridge is no longer supported.
	 */
	@Deprecated
	public final static int ACCESS = 4;
	
	/**
	 * Constant identifying the MySQL database type.
	 */
	public final static int MYSQL = 5;
	
	/**
	 * Constant for legacy ODBC-bridge drivers. Deprecated and not supported
	 * by modern JVMs.
	 */
	@Deprecated
	public final static int BRIDGE = 6;
	
	/**
	 * Constant identifying the PostgreSQL database type.
	 */
	public final static int POSTGRES = 7;
	
	/**
	 * Constant identifying the IBM DB2 database type.
	 */
	public final static int DB2 = 8;

	/**
	 * The active database type for this metadata, represented by one of the
	 * predefined constants (e.g., {@link #POSTGRES}, {@link #ORACLE}).
	 */
	public int databaseType;

	/**
	 * Optional note field for storing descriptive or administrative remarks
	 * about this database configuration.
	 */
	public String note;
	
	/**
	 * The display name for this database configuration.
	 */
	public String name;
	
	/**
	 * A descriptive summary for this database metadata instance.
	 */
	public String description;
	
	/**
	 * Flag indicating whether table and column names should be wrapped in
	 * square brackets (e.g., {@code [ColumnName]}). Typically used for
	 * SQL Server–style quoting.
	 */
	public boolean useBracket = true; // use "[" and "]" around table and column names
	
	/**
	 * The left-side bracket string used when {@link #useBracket} is enabled.
	 */
	public String leftBracket = "";
	
	/**
	 * The right-side bracket string used when {@link #useBracket} is enabled.
	 */
	public String rightBracket = "";
	
	/**
	 * Keyword used to indicate distinct selection in SQL queries.
	 */
	public String distinctKeyword = "DISTINCT";
	
	/**
	 * Flag indicating whether blank strings should be treated as NULL values
	 * when reading or writing to the database.
	 */
	public boolean blanksAsNulls = false;
	
	/**
	 * True if vendor-specific outer-join escape syntax is required (e.g.,
	 * JDBC escape syntax {@code {oj ...}}).
	 */
	public boolean useOuterJoinEscape = false;
	
	/**
	 * Prefix string used for vendor-specific outer-join escape syntax.
	 */
	public String outerjoinStart = "";
	
	/**
	 * Suffix string used for vendor-specific outer-join escape syntax.
	 */
	public String outerjoinEnd = "";
	
	
	/**
	 * Database-specific literal values used to represent Boolean {@code true} and {@code false}
	 * when storing boolean fields in databases that do not support native Boolean types.
	 */
	public Object objectTrue, objectFalse; // values to use for storing Boolean properties

	/**
	 * Indicates whether Boolean values should be emitted as unquoted SQL
	 * keywords (e.g., {@code TRUE}, {@code FALSE}) rather than string literals.
	 */
	public boolean booleanKeyword; // boolean values use a keyword.  ex: TRUE instead of 'TRUE'
	
	/**
	 * Flag indicating whether the database stores {@code DATE} values with an
	 * implicit time component, requiring additional handling during reads and writes.
	 */
	public boolean datesIncludeTime; // flag to know that the DB saves Dates as both Date and Time
	
	/**
	 * Determines whether generated SQL should use the {@code EXISTS} clause
	 * instead of alternative approaches for subquery evaluation.
	 */
	public boolean useExists = true;
	
	/**
	 * Specifies whether the database uses the backslash ("\\") as an escape
	 * character. Currently unused, as no supported engines require this.
	 */
	public boolean useBackslashForEscape = false; // NOTE: no database are currently set up as true
	
	/**
	 * Indicates whether the target database treats identifiers and string
	 * comparison as case-sensitive.
	 */
	public boolean caseSensitive; // for case sensitive databases
	
	/**
	 * SQL function name used to convert text values to lowercase
	 * (e.g., {@code LOWER}, {@code LCASE}).
	 */
	public String lowerCaseFunction;
	
	/**
	 * Flag indicating whether the database automatically assigns primary key
	 * values (such as identity columns or sequences).
	 */
	public boolean supportsAutoAssign; // if true, db assigns id
	
	/**
	 * Optional literal or expression representing the auto-assigned value for
	 * primary key fields, when supported by the database.
	 */
	public String autoAssignValue;
	
	/**
	 * Optional SQL type definition used for auto-assigned identifier columns.
	 */
	public String autoAssignType; // "optional" column type for auto assign
	
	/**
	 * Database-specific casting syntax for JSON types,
	 * such as PostgreSQL's {@code ::jsonb}.
	 */
	public String jsonCast; // ex:  postgres needs '::jsonb'

	// 20200511 uses statement.setMaxRows(x) instead
	// public String maxString; // ex: "LIMIT ?"; // use "?" to have the max amount entered
	/**
	 * Database-specific expression or function used to generate GUID/UUID values.
	 */
	public String guid;

	/**
	 * Controls whether JDBC statement pooling should be enabled for this database configuration.
	 */
	public boolean allowStatementPooling = true;
	
	/**
	 * Indicates whether foreign-key columns should automatically receive an index
	 * when DDL scripts are generated.
	 */
	public boolean fkeysAutoCreateIndex = false;
	
	/**
	 * Maximum supported length for VARCHAR-like text columns in the target database.
	 */
	public int maxVarcharLength;
	
	/**
	 * SQL keyword used for pattern matching operations.
	 * Default is {@code LIKE}, but can vary for certain engines.
	 */
	public String likeKeyword = "LIKE";
	
	/**
	 * Indicates whether the database supports SQL {@code LIMIT} syntax
	 * for restricting the number of returned rows.
	 */
	public boolean supportsLimit;
	
	/**
	 * Indicates whether the database supports {@code FETCH FIRST n ROWS ONLY}
	 * syntax as an alternative to {@code LIMIT}.
	 */
	public boolean supportsFetchFirst;

	/*
	    DERBY:                   "org.apache.derby.jdbc.EmbeddedDriver"
	    MS SQL Server            "com.microsoft.sqlserver.jdbc.SQLServerDriver"

	    ODBC Bridge:             "sun.jdbc.odbc.JdbcOdbcDriver"
	    INET (SQL-SERVER):       "com.inet.tds.TdsDriver"
	    WEBLOGIC (SQL-SERVER):   "weblogic.jdbc.mssqlserver4.Driver"
	    ODBC-BRIDGE:             "sun.jdbc.odbc.JdbcOdbcDriver"
	    ORACLE:                  "oracle.jdbc.driver.OracleDriver"
		Postgres:                "org.postgresql.ds.PGSimpleDataSource"
		DB2:                     "com.ibm.as400.access.AS400JDBCDriver"
	*/
	/**
	 * Fully qualified name of the JDBC driver class associated with the
	 * configured database engine.
	 */
	public String driverJDBC;

	/*
	    Derby:                   "jdbc:derby:database"   to create: "jdbc:derby:database;create=true;collation=TERRITORY_BASED"
	    MS SQL Server            "jdbc:sqlserver://localhost;port=1433;database=vetjobs;sendStringParametersAsUnicode=false;SelectMethod=cursor;ConnectionRetryCount=2;ConnectionRetryDelay=2"

	    INET (SQL-SERVER):       "jdbc:inetdae:127.0.0.1:1433?database=northwind&sql7=true"
	    WEBLOGIC (SQL-SERVER):   "jdbc:weblogic:mssqlserver4:northwind@127.0.0.1:1433"
	    ODBC-BRIDGE:             "jdbc:odbc:northwind"
	    Access:                  "jdbc:odbc:Driver={Microsoft Access Driver (*.mdb)};Dbq=c:\\temp\\vetplan.mdb";
		Postgres:                "jdbc:postgresql://$Host:5432/$DatabaseName"
		DB2:                     "jdbc:as400://as400/;libraries=JBRSYS,JBRDATA,CLOCFILE00,QZRDSSRV,SYSIBM,QGPL;errors=full;naming=system;driver=native;"
	*/

	/**
	 * JDBC connection URL used to connect to the target database instance.
	 */
	public String urlJDBC;

	/**
	 * Username used when establishing JDBC connections to the database.
	 */
	public String user;

	/**
	 * Password used when establishing JDBC connections to the database.
	 */
	public String password;

	/**
	 * Maximum number of concurrent JDBC connections allowed in the connection pool.
	 */
	public int maxConnections = 10;

	/**
	 * Minimum number of JDBC connections maintained in the connection pool.
	 */
	public int minConnections = 3;

	/**
	 * Default constructor creating an uninitialized {@link DBMetaData} instance.
	 * Callers must explicitly configure database type and connection parameters.
	 */
	public DBMetaData() {
	}

	/**
	 * Constructs a new metadata object for the specified database type.
	 *
	 * @param databaseType one of the {@code DBMetaData} database-type constants
	 */
	public DBMetaData(int databaseType) {
		setDatabaseType(databaseType);
	}

	/**
	 * Constructs a fully configured {@link DBMetaData} instance.
	 *
	 * @param databaseType the database engine type constant
	 * @param user the JDBC username
	 * @param password the JDBC password
	 * @param driverJDBC the driver class name
	 * @param urlJDBC the JDBC connection URL
	 */
	public DBMetaData(int databaseType, String user, String password, String driverJDBC, String urlJDBC) {
		setDatabaseType(databaseType);
		setUser(user);
		setPassword(password);
		setDriverJDBC(driverJDBC);
		setUrlJDBC(urlJDBC);
	}

	/**
	 * Updates the database type and triggers delegate notification when not loading.
	 *
	 * @param dbType one of the defined database-type constants
	 */
	public void setDatabaseType(int dbType) {
		int old = this.databaseType;
		this.databaseType = dbType;
		firePropertyChange("databaseType", old, this.databaseType);
		if (!isLoading()) {
			DBMetaDataDelegate.updateAfterTypeChange(this);
		}
	}

	/**
	 * Returns the configured database type.
	 *
	 * @return the {@code DBMetaData} type constant
	 */
	public int getDatabaseType() {
		return databaseType;
	}

	/**
	 * Retrieves the logical name assigned to this metadata configuration.
	 *
	 * @return the metadata name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Updates the name for this metadata configuration.
	 *
	 * @param name descriptive name
	 */
	public void setName(String name) {
		String old = this.name;
		this.name = name;
		firePropertyChange("name", old, this.name);
	}

	/**
	 * Returns any custom notes associated with this metadata entry.
	 *
	 * @return optional free-form note text
	 */
	public String getNote() {
		return note;
	}

	/**
	 * Updates the note text for this metadata entry.
	 *
	 * @param note descriptive or contextual note text
	 */
	public void setNote(String note) {
		String old = this.note;
		this.note = note;
		firePropertyChange("note", old, this.note);
	}

	/**
	 * Returns the descriptive text associated with this metadata entry.
	 *
	 * @return description of this configuration
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * Updates the descriptive text for this metadata entry.
	 *
	 * @param description human-readable description
	 */
	public void setDescription(String description) {
		String old = this.description;
		this.description = description;
		firePropertyChange("description", old, this.description);
	}

	/**
	 * Indicates whether indexes are automatically created for foreign-key columns.
	 *
	 * @return {@code true} if indexes should be auto-created
	 */
	public boolean getFkeysAutoCreateIndex() {
		return fkeysAutoCreateIndex;
	}

	/**
	 * Sets whether foreign-key columns should automatically receive indexes.
	 *
	 * @param b {@code true} to enable auto index creation
	 */
	public void setFkeysAutoCreateIndex(boolean b) {
		boolean old = this.fkeysAutoCreateIndex;
		this.fkeysAutoCreateIndex = b;
		firePropertyChange("fkeysAutoCreateIndex", old, fkeysAutoCreateIndex);
	}

	/**
	 * Returns whether JDBC statement pooling is allowed for this metadata.
	 *
	 * @return {@code true} if pooling is permitted
	 */
	public boolean getAllowStatementPooling() {
		return allowStatementPooling;
	}

	/**
	 * Enables or disables JDBC statement pooling.
	 *
	 * @param b {@code true} to allow statement pooling
	 */
	public void setAllowStatementPooling(boolean b) {
		boolean old = this.allowStatementPooling;
		this.allowStatementPooling = b;
		firePropertyChange("allowStatementPooling", old, allowStatementPooling);
	}

	/**
	 * Indicates if table/column identifiers should be wrapped in brackets.
	 *
	 * @return {@code true} if bracket quoting is enabled
	 */
	public boolean getUseBracket() {
		return useBracket;
	}

	/**
	 * Enables or disables bracket-based quoting of identifiers. Automatically
	 * updates {@link #leftBracket} and {@link #rightBracket} when not loading.
	 *
	 * @param useBracket {@code true} to use '[' and ']' for quoting
	 */
	public void setUseBracket(boolean useBracket) {
		boolean old = this.useBracket;
		this.useBracket = useBracket;
		firePropertyChange("useBracket", old, this.useBracket);
		if (!isLoading()) {
			if (useBracket) {
				setLeftBracket("[");
				setRightBracket("]");
			} else {
				setLeftBracket("");
				setRightBracket("");
			}
		}
	}

	/**
	 * Returns the left bracket or quoting symbol used for SQL identifiers.
	 *
	 * @return left bracket symbol
	 */
	public String getLeftBracket() {
		return leftBracket;
	}

	/**
	 * Updates the left-hand quoting symbol used for SQL identifier wrapping.
	 *
	 * @param leftBracket new left bracket symbol
	 */
	public void setLeftBracket(String leftBracket) {
		String old = this.leftBracket;
		this.leftBracket = leftBracket;
		firePropertyChange("leftBracket", old, this.leftBracket);
	}

	/**
	 * Returns the right bracket or quoting symbol used for SQL identifiers.
	 *
	 * @return right bracket symbol
	 */
	public String getRightBracket() {
		return rightBracket;
	}

	/**
	 * Updates the right-hand quoting symbol used for SQL identifier wrapping.
	 *
	 * @param rightBracket new right bracket symbol
	 */
	public void setRightBracket(String rightBracket) {
		String old = this.rightBracket;
		this.rightBracket = rightBracket;
		firePropertyChange("rightBracket", old, this.rightBracket);
	}

	/**
	 * Returns the SQL keyword used to request distinct query results.
	 *
	 * @return the distinct keyword (default is {@code "DISTINCT"})
	 */
	public String getDistinctKeyword() {
		return distinctKeyword;
	}

	/**
	 * Updates the SQL distinct keyword used when generating queries.
	 *
	 * @param distinctKeyword the keyword to use for DISTINCT operations
	 */
	public void setDistinctKeyword(String distinctKeyword) {
		String old = this.distinctKeyword;
		this.distinctKeyword = distinctKeyword;
		firePropertyChange("distinctKeyword", old, this.distinctKeyword);
	}

	/**
	 * Determines whether empty strings should be persisted as SQL {@code NULL}.
	 *
	 * @return {@code true} if blanks are converted to NULL
	 */
	public boolean getBlanksAsNulls() {
		return blanksAsNulls;
	}

	/**
	 * Sets the behavior for mapping empty strings to SQL {@code NULL}.
	 *
	 * @param blanksAsNulls {@code true} to treat blanks as NULL
	 */
	public void setBlanksAsNulls(boolean blanksAsNulls) {
		boolean old = this.blanksAsNulls;
		this.blanksAsNulls = blanksAsNulls;
		firePropertyChange("blanksAsNulls", old, this.blanksAsNulls);
	}

	/**
	 * Returns whether ODBC escape syntax should be used for outer joins.
	 *
	 * @return {@code true} if escape syntax is enabled
	 */
	public boolean getUseOuterJoinEscape() {
		return useOuterJoinEscape;
	}

	/**
	 * Enables or disables ODBC escape syntax for outer joins. Automatically
	 * adjusts {@link #outerjoinStart} and {@link #outerjoinEnd} when not loading.
	 *
	 * @param useOuterJoinEscape {@code true} to enable escape syntax
	 */
	public void setUseOuterJoinEscape(boolean useOuterJoinEscape) {
		boolean old = this.useOuterJoinEscape;
		this.useOuterJoinEscape = useOuterJoinEscape;
		firePropertyChange("useOuterJoinEscape", old, this.useOuterJoinEscape);
		if (!isLoading()) {
			if (useOuterJoinEscape) {
				setOuterjoinStart("{oj ");
				setOuterjoinEnd("}");
			} else {
				setOuterjoinStart("");
				setOuterjoinEnd("");
			}
		}
	}

	/**
	 * Returns the opening token used for outer join escape syntax.
	 *
	 * @return the escape-start token
	 */
	public String getOuterjoinStart() {
		return outerjoinStart;
	}

	/**
	 * Updates the opening token used for ODBC outer join escape syntax.
	 *
	 * @param outerjoinStart the new opening token
	 */
	public void setOuterjoinStart(String outerjoinStart) {
		String old = this.outerjoinStart;
		this.outerjoinStart = outerjoinStart;
		firePropertyChange("outerjoinStart", old, this.outerjoinStart);
	}

	/**
	 * Returns the closing token used for outer join escape syntax.
	 *
	 * @return the escape-end token
	 */
	public String getOuterjoinEnd() {
		return outerjoinEnd;
	}

	/**
	 * Updates the closing token used for ODBC outer join escape syntax.
	 *
	 * @param outerjoinEnd the new closing token
	 */
	public void setOuterjoinEnd(String outerjoinEnd) {
		Object old = this.outerjoinEnd;
		this.outerjoinEnd = outerjoinEnd;
		firePropertyChange("outerjoinEnd", old, this.outerjoinEnd);
	}

	/**
	 * Retrieves the database-specific literal value representing Boolean {@code true}.
	 *
	 * @return literal value used for TRUE
	 */
	public Object getObjectTrue() {
		return objectTrue;
	}

	/**
	 * Assigns the literal value that represents Boolean {@code true} in the
	 * underlying database.
	 *
	 * @param objectTrue the literal TRUE value
	 */
	public void setObjectTrue(Object objectTrue) {
		Object old = this.objectTrue;
		this.objectTrue = objectTrue;
		firePropertyChange("objectTrue", old, this.objectTrue);
	}

	/**
	 * Retrieves the database-specific literal value representing Boolean {@code false}.
	 *
	 * @return literal value used for FALSE
	 */
	public Object getObjectFalse() {
		return objectFalse;
	}

	/**
	 * Assigns the literal value that represents Boolean {@code false} in the
	 * underlying database.
	 *
	 * @param objectFalse the literal FALSE value
	 */
	public void setObjectFalse(Object objectFalse) {
		Object old = this.objectFalse;
		this.objectFalse = objectFalse;
		firePropertyChange("objectFalse", old, this.objectFalse);
	}

	/**
	 * Indicates whether Boolean values should be emitted as SQL keywords
	 * rather than string literals.
	 *
	 * @return {@code true} if Boolean keywords should be used
	 */
	public boolean getBooleanKeyword() {
		return booleanKeyword;
	}

	/**
	 * Sets whether Boolean values are emitted as SQL keywords (e.g., TRUE/FALSE)
	 * instead of string literals.
	 *
	 * @param booleanKeyword {@code true} to use keyword form
	 */
	public void setBooleanKeyword(boolean booleanKeyword) {
		boolean old = this.booleanKeyword;
		this.booleanKeyword = booleanKeyword;
		firePropertyChange("booleanKeyword", old, this.booleanKeyword);
	}

	/**
	 * Indicates whether the database stores DATE values with an implicit
	 * time component.
	 *
	 * @return {@code true} if dates contain time
	 */
	public boolean getDatesIncludeTime() {
		return datesIncludeTime;
	}

	/**
	 * Updates the flag indicating whether DATE fields include time information.
	 *
	 * @param bDatesIncludeTime {@code true} if time is included
	 */
	public void setDatesIncludeTime(boolean bDatesIncludeTime) {
		boolean old = this.datesIncludeTime;
		this.datesIncludeTime = bDatesIncludeTime;
		firePropertyChange("DatesIncludeTime", old, this.datesIncludeTime);
	}

	/**
	 * Determines whether EXISTS clauses should be used in generated SQL
	 * for certain query structures.
	 *
	 * @return {@code true} if EXISTS is preferred
	 */
	public boolean getUseExists() {
		return useExists;
	}

	/**
	 * Enables or disables the use of EXISTS clauses in generated SQL.
	 *
	 * @param useExists {@code true} to use EXISTS
	 */
	public void setUseExists(boolean useExists) {
		boolean old = this.useExists;
		this.useExists = useExists;
		firePropertyChange("useExists", old, this.useExists);
	}

	/**
	 * Indicates whether the database uses the backslash character ("\\") for escaping.
	 *
	 * @return {@code true} if backslash escaping is required
	 */
	public boolean getUseBackslashForEscape() {
		return useBackslashForEscape;
	}

	/**
	 * Sets whether the database uses backslash ("\\") as an escape character.
	 *
	 * @param useBackslashForEscape {@code true} to enable backslash escaping
	 */
	public void setUseBackslashForEscape(boolean useBackslashForEscape) {
		boolean old = this.useBackslashForEscape;
		this.useBackslashForEscape = useBackslashForEscape;
		firePropertyChange("useBackslashForEscape", old, this.useBackslashForEscape);
	}

	/**
	 * Indicates whether the database treats identifiers and comparisons
	 * as case-sensitive.
	 *
	 * @return {@code true} if case sensitivity is enabled
	 */
	public boolean getCaseSensitive() {
		return caseSensitive;
	}

	/**
	 * Updates whether identifiers and comparisons should be treated as case-sensitive.
	 *
	 * @param caseSensitive {@code true} to enable case sensitivity
	 */
	public void setCaseSensitive(boolean caseSensitive) {
		boolean old = this.caseSensitive;
		this.caseSensitive = caseSensitive;
		firePropertyChange("caseSensitive", old, this.caseSensitive);
	}

	/**
	 * Returns the SQL function name used to convert values to lowercase.
	 *
	 * @return the lowercase SQL function
	 */
	public String getLowerCaseFunction() {
		return lowerCaseFunction;
	}

	/**
	 * Sets the SQL function used to convert text values to lowercase.
	 *
	 * @param lowerCaseFunction SQL function name, such as {@code "LOWER"}
	 */
	public void setLowerCaseFunction(String lowerCaseFunction) {
		String old = this.lowerCaseFunction;
		this.lowerCaseFunction = lowerCaseFunction;
		firePropertyChange("lowerCaseFunction", old, this.lowerCaseFunction);
	}

	/**
	 * Indicates whether the database provides automatic assignment of
	 * primary-key values (identity columns, sequences, etc.).
	 *
	 * @return {@code true} if auto-assign is supported
	 */
	public boolean getSupportsAutoAssign() {
		return supportsAutoAssign;
	}

	/**
	 * Enables or disables support for automatic primary-key assignment.
	 *
	 * @param supportsAutoAssign {@code true} to enable auto-assign
	 */
	public void setSupportsAutoAssign(boolean supportsAutoAssign) {
		boolean old = this.supportsAutoAssign;
		this.supportsAutoAssign = supportsAutoAssign;
		firePropertyChange("supportsAutoAssign", old, this.supportsAutoAssign);
	}

	/**
	 * Indicates whether the SQL {@code LIMIT} clause is supported by the database.
	 *
	 * @return {@code true} if LIMIT is supported
	 */
	public boolean getSupportsLimit() {
		return supportsLimit;
	}

	/**
	 * Sets whether SQL {@code LIMIT} syntax is supported for row limiting.
	 *
	 * @param supportsLimit {@code true} to enable LIMIT support
	 */
	public void setSupportsLimit(boolean supportsLimit) {
		boolean old = this.supportsLimit;
		this.supportsLimit = supportsLimit;
		firePropertyChange("supportsLimit", old, this.supportsLimit);
	}

	/**
	 * Returns whether the database supports the {@code FETCH FIRST n ROWS ONLY}
	 * syntax for row limiting.
	 *
	 * @return {@code true} if FETCH FIRST is supported
	 */
	public boolean getSupportsFetchFirst() {
		return supportsFetchFirst;
	}

	/**
	 * Enables or disables support for the {@code FETCH FIRST} row-limiting syntax.
	 *
	 * @param supportsFetchFirst {@code true} to enable support
	 */
	public void setSupportsFetchFirst(boolean supportsFetchFirst) {
		boolean old = this.supportsFetchFirst;
		this.supportsFetchFirst = supportsFetchFirst;
		firePropertyChange("supportsFetchFirst", old, this.supportsFetchFirst);
	}

	/**
	 * Returns the SQL literal or expression representing the auto-assign value
	 * used for primary-key fields.
	 *
	 * @return auto-assign literal or expression
	 */
	public String getAutoAssignValue() {
		return autoAssignValue;
	}

	/**
	 * Updates the literal or expression used for auto-assigned primary-key values.
	 *
	 * @param autoAssignValue the auto-assign expression
	 */
	public void setAutoAssignValue(String autoAssignValue) {
		String old = this.autoAssignValue;
		this.autoAssignValue = autoAssignValue;
		firePropertyChange("autoAssignValue", old, this.autoAssignValue);
	}

	/**
	 * Returns the SQL column type used for auto-assigned identifier fields.
	 *
	 * @return SQL type for auto-assign columns
	 */
	public String getAutoAssignType() {
		return autoAssignType;
	}

	/**
	 * Sets the SQL column type associated with auto-assigned identifiers.
	 *
	 * @param autoAssignType the auto-assign SQL type
	 */
	public void setAutoAssignType(String autoAssignType) {
		String old = this.autoAssignType;
		this.autoAssignType = autoAssignType;
		firePropertyChange("autoAssignType", old, this.autoAssignType);
	}

	/*
	public String getMaxString() {
		return maxString;
	}
	public void setMaxString(String maxString) {
		String old = this.maxString;
		this.maxString = maxString;
		firePropertyChange("maxString", old, this.maxString);
	}
	*/

	/**
	 * Returns the JDBC driver class name associated with this metadata configuration.
	 *
	 * @return JDBC driver class name
	 */
	public String getDriverJDBC() {
		return driverJDBC;
	}

	/**
	 * Sets the JDBC driver class name used to establish connections.
	 *
	 * @param driverJDBC fully qualified driver class name
	 */
	public void setDriverJDBC(String driverJDBC) {
		String old = this.driverJDBC;
		this.driverJDBC = driverJDBC;
		firePropertyChange("driverJDBC", old, this.driverJDBC);
	}

	/**
	 * Returns the JDBC connection URL for the configured database.
	 *
	 * @return JDBC URL
	 */
	public String getUrlJDBC() {
		return urlJDBC;
	}

	/**
	 * Updates the JDBC connection URL for this metadata configuration.
	 *
	 * @param urlJDBC JDBC connection URL
	 */
	public void setUrlJDBC(String urlJDBC) {
		String old = this.urlJDBC;
		this.urlJDBC = urlJDBC;
		firePropertyChange("urlJDBC", old, this.urlJDBC);
	}

	/**
	 * Returns the username used for JDBC authentication.
	 *
	 * @return JDBC username
	 */
	public String getUser() {
		return user;
	}

	/**
	 * Sets the username used when establishing JDBC connections.
	 *
	 * @param user JDBC username
	 */
	public void setUser(String user) {
		String old = this.user;
		this.user = user;
		firePropertyChange("user", old, this.user);
	}

	/**
	 * Returns the password used for JDBC authentication.
	 *
	 * @return JDBC password
	 */
	public String getPassword() {
		return password;
	}

	/**
	 * Updates the password used when establishing JDBC connections.
	 *
	 * @param password JDBC password
	 */
	public void setPassword(String password) {
		String old = this.password;
		this.password = password;
		firePropertyChange("password", old, this.password);
	}

	/**
	 * Returns the maximum number of connections allowed in the connection pool.
	 *
	 * @return maximum pool size
	 */
	public int getMaxConnections() {
		return maxConnections;
	}

	/**
	 * Sets the maximum number of JDBC connections that may exist in the pool.
	 *
	 * @param maxConnections maximum connection count
	 */
	public void setMaxConnections(int maxConnections) {
		int old = this.maxConnections;
		this.maxConnections = maxConnections;
		firePropertyChange("maxConnections", old, this.maxConnections);
	}

	/**
	 * Returns the minimum number of persistent connections maintained in the pool.
	 *
	 * @return minimum pool size
	 */
	public int getMinConnections() {
		return minConnections;
	}

	/**
	 * Updates the minimum number of JDBC connections maintained in the pool.
	 *
	 * @param minConnections minimum connection count
	 */
	public void setMinConnections(int minConnections) {
		int old = this.minConnections;
		this.minConnections = minConnections;
		firePropertyChange("minConnections", old, this.minConnections);
	}

	//========================= Object Info ============================
	/**
	 * Returns the static {@link OAObjectInfo} metadata for this class, including
	 * initialization rules, link metadata, and calculation definitions.
	 *
	 * @return {@link OAObjectInfo} instance describing this class
	 */
	public static OAObjectInfo getOAObjectInfo() {
		return oaObjectInfo;
	}

	/**
	 * Static metadata describing the OAObject model information for this class,
	 * including links, calculated properties, and initialization settings.
	 */
	protected static OAObjectInfo oaObjectInfo;
	static {
		// OALinkInfo(property, toClass, ONE/MANY, cascadeSave, cascadeDelete, reverseProperty, allowDelete, owner, recursive)
		oaObjectInfo = new OAObjectInfo(new String[] {});
		oaObjectInfo.setInitializeNewObjects(false);

		// oaObjectInfo.addLink(new OALinkInfo("objectDefs",   ObjectDef.class,  OALinkInfo.MANY, true,true, "model"));

		// OACalcInfo(calcPropertyName, String[] { propertyPath1, propertyPathN })
		// oaObjectInfo.addCalc(new OACalcInfo("fullFileName", new String[] {"fileName","directoryName"} ));
	}

	/**
	 * Returns the maximum supported VARCHAR size for the configured database engine.
	 *
	 * @return maximum VARCHAR length
	 */
	public int getMaxVarcharLength() {
		return maxVarcharLength;
	}

	/**
	 * Updates the maximum supported VARCHAR size.
	 *
	 * @param x maximum allowed VARCHAR length
	 */
	public void setMaxVarcharLength(int x) {
		int old = this.maxVarcharLength;
		this.maxVarcharLength = x;
		firePropertyChange("maxVarcharLength", old, this.maxVarcharLength);
	}

	/**
	 * Returns the SQL keyword used for pattern matching comparisons.
	 * Typically {@code LIKE}.
	 *
	 * @return keyword used for LIKE operations
	 */
	public String getLikeKeyword() {
		return likeKeyword;
	}

	/**
	 * Updates the SQL keyword used for pattern matching.
	 *
	 * @param newLikeKeyword replacement for the LIKE predicate keyword
	 */
	public void setLikeKeyword(String newLikeKeyword) {
		String old = this.likeKeyword;
		this.likeKeyword = newLikeKeyword;
		firePropertyChange("likeKeyword", old, this.likeKeyword);
	}
}
