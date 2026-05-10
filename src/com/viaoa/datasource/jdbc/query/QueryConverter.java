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
package com.viaoa.datasource.jdbc.query;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.logging.Logger;

import com.viaoa.datasource.jdbc.OADataSourceJDBC;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.datasource.jdbc.db.DataAccessObject;
import com.viaoa.datasource.jdbc.db.Database;
import com.viaoa.datasource.jdbc.db.Link;
import com.viaoa.datasource.jdbc.db.Table;
import com.viaoa.datasource.jdbc.delegate.ConverterDelegate;
import com.viaoa.graph.OAGraphInternal;
import com.viaoa.graph.service.object.OAObjectInfoService;
import com.viaoa.graph.service.object.OAObjectKeyService;
import com.viaoa.graph.service.object.OAObjectReflectService;
import com.viaoa.lang.OAArray;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OALinkInfo;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.query.OAQueryToken;
import com.viaoa.query.OAQueryTokenType;
import com.viaoa.query.OAQueryTokenizer;
import com.viaoa.runtime.OARuntime;

/*qqqqqqqqqqqqqqqqqqqqq
CODEX 
v4.0

  - Severity: high
  - File/method: src/main/java/com/viaoa/datasource/jdbc/query/QueryConverter.java:428, src/main/java/com/viaoa/
    datasource/jdbc/query/QueryConverter.java:869
  - Finding: convertToWhere() can set bUseExists = false for link-table paths, but the nested convertToSql(...) call
    resets converter state again, losing that decision.
  - Why it matters: SQL join/EXISTS choice can be wrong for where-object/link-table queries.
  - PoC impact: Likely for many-to-many or link-table select paths.
  - Direction: Avoid the second reset, or pass conversion options explicitly into the core SQL builder.


 - Severity: high
  - File/method: src/main/java/com/viaoa/datasource/jdbc/query/QueryConverter.java:1722
  - Finding: SQL conversion parses property paths with StringTokenizer, not OAPropertyPath. It does not share
    OAPropertyPath casting, validation, filters, root class qualifier, or semantic metadata behavior.
  - Why it matters: OA query paths are semantic object-graph paths. DB translation can diverge from OA runtime path
    resolution.
  - PoC impact: Medium now; higher as OA 4.0 leans on generated paths.
  - Direction: Have SQL conversion consume a graph-owned parsed property-path representation, then map that to DB
    links/columns.

  #7 — FIX NOW
  Location: src/main/java/com/viaoa/datasource/jdbc/query/QueryConverter.java:1749
  parseLink() uses StringTokenizer on dots, not OAPropertyPath. It misses OAPropertyPath validation/semantics and
  can diverge from runtime path resolution. This is the biggest architectural correctness risk in the descendant
  parser.

 #8 — FIX NOW
  Location: src/main/java/com/viaoa/datasource/jdbc/query/QueryConverter.java:1755
  parseLink("") or malformed empty path can throw NoSuchElementException, not a controlled query exception.

  #9 — FIX NOW
  Location: src/main/java/com/viaoa/datasource/jdbc/query/QueryConverter.java:1515
  In EXISTS generation, the loop over composite foreign keys resets exists = ... each iteration after adding " AND
  ", so earlier key comparisons can be lost. Composite-key many paths are likely wrong.

  #10 — DEFER
  Location: src/main/java/com/viaoa/datasource/jdbc/query/QueryConverter.java:969
  ORDER BY parsing is whitespace/comma token based. Function calls with spaces or richer expressions are fragile.
  Probably acceptable for simple OA property order clauses, but should be covered by tests.


*/

/**
 * Translates OA object queries into executable SQL statements.
 * <p>
 * Handles property paths, inheritance joins, and link-table relationships. Supports both JOIN and EXISTS-based
 * translation modes depending on database capabilities and query complexity.
 * <p>
 * Features:
 * <ul>
 *   <li>Parses OA query syntax using {@link com.viaoa.query.OAQueryTokenizer}.</li>
 *   <li>Generates SELECT, FROM, WHERE, and ORDER BY clauses with proper aliasing and quoting.</li>
 *   <li>Supports prepared statements and parameter substitution.</li>
 *   <li>Automatically applies DISTINCT when many-side joins are detected.</li>
 *   <li>Resolves super/subclass table hierarchies through {@link com.viaoa.datasource.jdbc.db.Database} metadata.</li>
 * </ul>
 * This class is used internally by {@link com.viaoa.datasource.jdbc.db.DataAccessObject}
 * when creating or executing dynamic SQL queries.
 */
public class QueryConverter {
	private static Logger LOG = Logger.getLogger(QueryConverter.class.getName());

	/**
	 * Metadata describing database features, column quoting rules, and dialect-specific
	 * behaviors used while translating queries to SQL.
	 */
	private DBMetaData dbmd;
	
	/**
	 * Reference to the database schema definition, including tables, columns, and link
	 * relationships used to resolve property paths and join structures.
	 */
	private Database database;
	
	/**
	 * Flag indicating that the whereObject does not resolve to any records, causing
	 * query generation to return empty SQL results.
	 */
	private boolean bEmpty; // if set, then the whereObject does not have any records to select
	
	/**
	 * Optional object used as the direct result of a selection, bypassing normal
	 * query translation when non-null.
	 */
	private Object selectObject; // the object to use in place of select, instead of using query
	
	/**
	 * Root link information node representing the base table and its relationship
	 * hierarchy, used by join construction methods.
	 */
	private Linkinfo root; // root of tree that keeps track of tables/objects that are needed for query.  Method getJoins() uses this to build Join clause
	
	/**
	 * Counter used internally by Linkinfo instances to allocate unique numbering
	 * for aliased table references.
	 */
	private int counter; // used by LinkInfo
	
	/**
	 * Indicates that all joins should be generated as LEFT OUTER JOIN operations,
	 * generally triggered when OR conditions are present.
	 */
	private boolean bUseLeftJoin; // all joins need to use "LEFT OUTER JOIN"
	
	/**
	 * Tracks whether the WHERE clause contains OR conditions, which can alter join
	 * strategy and affect the need for LEFT OUTER JOIN usage.
	 */
	private boolean bUsingOR; // an "OR" is used in where clause.  If other Joins will be needed then bUseLeftJoin will be set to true
	
	/**
	 * Determines whether EXISTS-based SQL should be used when resolving many-side
	 * relationships, depending on database capabilities and conditions.
	 */
	private boolean bUseExists;
	
	/**
	 * Left and right bracket characters used to quote identifiers such as table and
	 * column names, derived from database metadata.
	 */
	private String LB, RB;
	
	/**
	 * Additional WHERE clause content produced during join creation, particularly for
	 * Oracle outer-join emulation.
	 */
	private String extraWhere;
	
	/**
	 * True when DISTINCT must be applied to prevent duplicates resulting from
	 * many-side joins on the relationship graph.
	 */
	private boolean bUseDistinct;

	/**
	 * Constructs a QueryConverter using metadata and database information obtained
	 * from the provided JDBC data source.
	 *
	 * @param ds the JDBC-based data source supplying metadata and database schema
	 */
	public QueryConverter(OADataSourceJDBC ds) {
		this.dbmd = ds.getDBMetaData();
		this.database = ds.getDatabase();
		reset();
	}

	/**
	 * Constructs a QueryConverter using explicitly supplied database and metadata
	 * objects.
	 *
	 * @param database the database schema information
	 * @param dbmd     the database metadata used for SQL construction rules
	 */
	public QueryConverter(Database database, DBMetaData dbmd) {
		this.database = database;
		this.dbmd = dbmd;
		reset();
	}

	/**
	 * Indicates whether the SQL query requires DISTINCT because only primary key
	 * columns can be selected while preventing duplicate rows.
	 *
	 * @return true if DISTINCT should be applied; otherwise false
	 */
	public boolean getUseDistinct() {
		return bUseDistinct;
	}

	/**
	 * Resets internal state including identifier quoting characters, EXISTS-mode
	 * determination, and stored prepared-statement arguments.
	 */
	protected void reset() {
		this.LB = dbmd.leftBracket;
		this.RB = dbmd.rightBracket;
		this.bUseExists = dbmd.useExists;
		this.arguments = null;
	}

	/**
	 * Builds and returns a comma-separated list of primary-key column references
	 * (quoted with LB/RB) for the given class’s table.
	 *
	 * @param clazz the class representing the target table
	 * @return SQL column list for the table's primary key columns
	 */
	public String getPrimaryKeyColumns(Class clazz) {
		Table table = database.getTable(clazz);
		if (table == null) {
			return "";
		}

		if (table.selectPKColumns != null) {
			return table.selectPKColumns;
		}

		String strColumn = null;
		DataAccessObject dao = table.getDataAccessObject();
		if (dao != null) {
			strColumn = dao.getPkeySelectColumns();
		} else {
			String tableName = table.name.toUpperCase();

			Column[] cols = table.getColumns();
			for (int i = 0; cols != null && i < cols.length; i++) {
				if (!cols[i].primaryKey) {
					continue;
				}
				if (strColumn == null) {
					strColumn = "";
				} else {
					strColumn += ", ";
				}
				strColumn += LB + tableName + RB + "." + LB + cols[i].columnName.toUpperCase() + RB;
			}
		}
		table.selectPKColumns = strColumn;
		return strColumn;
	}

	// only used if table.DataAccessObject is not used
	/**
	 * Retrieves all selectable columns for the given class, including inherited and
	 * subclass columns, excluding byte[] properties.
	 *
	 * @param clazz the class whose table columns should be selected
	 * @return array of Column definitions used for SELECT clause construction
	 */
	public Column[] getSelectColumnArray(Class clazz) {
		Table tableMain = database.getTable(clazz);
		if (tableMain.selectColumnArray != null) {
			return tableMain.selectColumnArray;
		}

		Class[] classes = getSelectClasses(clazz);
		ArrayList al = null;

		for (int ii = 0; classes != null && ii < classes.length; ii++) {
			Class c = classes[ii];
			final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(c);
			OAObjectInfo oi = og.objectsInternal().callObjectInfoGetObjectInfo(c);

			Table table = database.getTable(c);
			String tableName = table.name.toUpperCase();

			// get all columns that map to properties
			Column[] columns = table.getSelectColumns();

			if (ii == 0 && classes.length == 1) {
				return columns;
			}
			if (al == null) {
				al = new ArrayList(25);
			}
			for (int i = 0; columns != null && i < columns.length; i++) {
				// 20121001 skip byte[]
				Class cz = og.objectsInternal().callObjectInfoGetPropertyClass(oi, columns[i].propertyName);
				if (cz.isArray()) {
					cz = cz.getComponentType();
					if (cz.equals(byte.class)) {
						continue;
					}
				}
				al.add(columns[i]);
			}
		}
		Column[] cols = new Column[al.size()];
		al.toArray(cols);
		tableMain.selectColumnArray = cols;
		return cols;
	}

	/**
	 * Returns a comma-separated list of SELECT column expressions for the given class,
	 * optionally regenerating cached results when dirty.
	 *
	 * @param clazz  the target class whose table columns should be selected
	 * @param bDirty if true, forces recalculation rather than using cached values
	 * @return SQL column list for SELECT clause generation
	 */
	public String getSelectColumns(Class clazz, boolean bDirty) {
		Table tableMain = database.getTable(clazz);
		if (!bDirty && tableMain.selectColumns != null) {
			return tableMain.selectColumns;
		}

		Class[] classes = getSelectClasses(clazz);
		String strColumn = "";
		for (int ii = 0; classes != null && ii < classes.length; ii++) {
			Class c = classes[ii];

			Table table = database.getTable(c);

			DataAccessObject dao = table.getDataAccessObject();
			if (!bDirty && dao != null) {
				if (strColumn.length() > 0) {
					strColumn += ", ";
				}
				strColumn += dao.getSelectColumns();
				tableMain.selectColumns = strColumn;
			} else {
				String tableName = table.name.toUpperCase();

				// get all columns that map to properties
				Column[] columns = table.getSelectColumns();
				for (int i = 0; columns != null && i < columns.length; i++) {
					if (ii > 0 || i > 0) {
						strColumn += ", ";
					}
					strColumn += LB + tableName + RB + "." + LB + columns[i].columnName.toUpperCase() + RB;
				}
			}
		}
		return strColumn;
	}

	/**
	 * Determines all classes participating in SELECT operations for the given start
	 * class, including superclasses and subclasses. The result is cached on the
	 * corresponding table.
	 *
	 * @param startClass the class whose inheritance hierarchy should be evaluated
	 * @return array of classes contributing columns to SELECT statements
	 */
	public Class[] getSelectClasses(Class startClass) {
		Table table = database.getTable(startClass);
		if (table.selectClasses != null) {
			return table.selectClasses;
		}

		Vector vec = new Vector(10, 10);
		getSelectSuperclasses(vec, startClass);
		getSelectSubclasses(vec, startClass, false);
		Class[] cs = new Class[vec.size()];
		vec.copyInto(cs);

		table.selectClasses = cs;
		return cs;
	}

	/**
	 * Recursively collects all superclasses of the given class (up to but excluding
	 * OAObject) into the provided vector, in hierarchical order.
	 *
	 * @param vec   vector receiving discovered superclass entries
	 * @param clazz the class whose superclasses are being processed
	 */
	private void getSelectSuperclasses(Vector vec, Class clazz) {
		Table table = database.getTable(clazz);
		if (table == null) {
			return;
		}

		Class c = clazz.getSuperclass();
		if (c != null && !(c.equals(OAObject.class))) {
			getSelectSuperclasses(vec, c);
		}
		vec.addElement(clazz);
	}

	/**
	 * Recursively collects all subclasses of the given class, optionally including the
	 * class itself, and adds them to the provided vector.
	 *
	 * @param vec          vector receiving discovered subclass entries
	 * @param clazz        the class whose subclasses are being processed
	 * @param bIncludeThis true to add the class itself before descending
	 */
	private void getSelectSubclasses(Vector vec, Class clazz, boolean bIncludeThis) {
		Table table = database.getTable(clazz);
		if (table == null) {
			return;
		}

		if (bIncludeThis) {
			vec.addElement(clazz);
		}

		Class[] subclasses = table.subclasses;
		for (int i = 0; subclasses != null && i < subclasses.length; i++) {
			getSelectSubclasses(vec, subclasses[i], true);
		}
	}

	/**
	 * Produces a full SQL statement using prepared-statement placeholders based on the
	 * supplied select class, where object, and ordering.
	 *
	 * @param selectClass             the root class for selection
	 * @param whereObject             object defining the link-based WHERE criteria
	 * @param propertyFromWhereObject link name used to evaluate the whereObject
	 * @param orderBy                 optional ORDER BY clause
	 * @return SQL query string using '?' placeholders
	 */
	public String convertForPreparedStatmentSql(Class selectClass, Object whereObject, String propertyFromWhereObject, String orderBy) {

		reset();
		Vector vecParam = new Vector(3, 3);
		String where = convertToWhere(selectClass, whereObject, propertyFromWhereObject, vecParam);

		Object[] params = new Object[vecParam.size()];
		vecParam.copyInto(params);

		String sql = convertToSql(selectClass, where, params, orderBy, true, true);

		return sql;
	}

	/**
	 * Builds a full SQL statement by combining link-based WHERE logic from the
	 * whereObject with an additional queryWhere clause, explicit extraWhere content,
	 * and ORDER BY settings.
	 *
	 * @param selectClass             root class used for table resolution
	 * @param whereObject             object defining link-based WHERE criteria
	 * @param propertyFromWhereObject link to follow from whereObject
	 * @param queryWhere              additional WHERE text to append
	 * @param args                    parameters associated with queryWhere
	 * @param extraWhere              optional extra WHERE content
	 * @param orderBy                 ORDER BY clause
	 * @return SQL string combining FROM, WHERE, and ORDER BY clauses
	 */
	public String convertToSql(Class selectClass, Object whereObject, String propertyFromWhereObject,
			String queryWhere, Object[] args,
			String extraWhere,
			String orderBy) {

		/*was
		public String convertToSql(Class selectClass, Object whereObject, String extraWhere, Object[] args, String propertyFromWhereObject,
			String orderBy) {
			*/

		reset();
		Vector vecParam = new Vector(3, 3);
		String s = convertToWhere(selectClass, whereObject, propertyFromWhereObject, vecParam);
		if (selectObject != null || bEmpty) {
			return "";
		}

		if (!OAString.isEmpty(queryWhere)) {
			if (!OAString.isEmpty(s)) {
				s = "(" + s + ") AND ";
			}
			s += queryWhere;
		}

		if (!OAString.isEmpty(extraWhere)) {
			if (!OAString.isEmpty(s)) {
				s = "(" + s + ") AND ";
			}
			s += extraWhere;
		}

		for (int i = 0; args != null && i < args.length; i++) {
			vecParam.add(args[i]);
		}
		Object[] params = new Object[vecParam.size()];
		vecParam.copyInto(params);

		return convertToSql(selectClass, s, params, orderBy);
	}

	/**
	 * Generates a full SQL statement using prepared-statement placeholders, combining
	 * link-based WHERE logic, additional WHERE expressions, and ordering.
	 *
	 * @param selectClass             the selection root class
	 * @param whereObject             object defining link-based WHERE criteria
	 * @param propertyFromWhereObject link to follow from the whereObject
	 * @param queryWhere              additional WHERE clause text
	 * @param args                    parameters for queryWhere
	 * @param extraWhere              extra WHERE clause text
	 * @param orderBy                 ORDER BY clause
	 * @return SQL string using '?' placeholders
	 */
	public String convertToPreparedStatementSql(Class selectClass,
			Object whereObject, String propertyFromWhereObject,
			String queryWhere, Object[] args,
			String extraWhere,
			String orderBy) {
		reset();
		Vector vecParam = new Vector(3, 3);
		String s = convertToWhere(selectClass, whereObject, propertyFromWhereObject, vecParam);
		if (selectObject != null || bEmpty) {
			return "";
		}

		if (!OAString.isEmpty(queryWhere)) {
			if (!OAString.isEmpty(s)) {
				s = "(" + s + ") AND ";
			}
			s += queryWhere;
		}

		if (!OAString.isEmpty(extraWhere)) {
			if (!OAString.isEmpty(s)) {
				s = "(" + s + ") AND ";
			}
			s += extraWhere;
		}

		for (int i = 0; args != null && i < args.length; i++) {
			vecParam.add(args[i]);
		}
		Object[] params = new Object[vecParam.size()];
		vecParam.copyInto(params);

		String sql = convertToSql(selectClass, s, params, orderBy, true, true);
		return sql;
	}

	/**
	 * Storage for parameters used during prepared-statement SQL generation.
	 */
	private Object[] arguments;

	/**
	 * Returns the prepared-statement argument array produced during SQL generation.
	 *
	 * @return argument array or null if none were generated
	 */
	public Object[] getArguments() {
		return arguments;
	}

	/**
	 * Produces the WHERE condition based on the link relationship from selectClass to
	 * whereObject, adding parameter values to the provided vector.
	 *
	 * @param selectClass             class representing the root table
	 * @param whereObject             object used to derive key values
	 * @param propertyFromWhereObject link name from the whereObject
	 * @param vecParam                vector receiving parameter values
	 * @return WHERE clause fragment or empty string if none applies
	 */
	private String convertToWhere(Class selectClass, Object whereObject, String propertyFromWhereObject, Vector vecParam) {
		String s = null;

		Table toTable;
		Class c;
		Class whereClass = whereObject.getClass();

		for (; s == null && whereClass != null && !(whereClass.equals(OAObject.class)); whereClass = whereClass.getSuperclass()) {
			toTable = database.getTable(whereClass);
			if (toTable == null) {
				return ""; // select all
			}

			c = selectClass;
			for (; s == null && c != null && !(c.equals(OAObject.class)); c = c.getSuperclass()) {
				Table fromTable = database.getTable(c);
				if (fromTable == null) {
					throw new RuntimeException("QueryConverter.convertToWhere() cant find link");
				}
				Link[] links = fromTable.getLinks();
				s = getWhere(links, fromTable, toTable, whereObject, propertyFromWhereObject, vecParam);
			}
		}

		// could not find it in direct links, now check any Link tables
		whereClass = whereObject.getClass();
		for (; s == null && whereClass != null && !(whereClass.equals(OAObject.class)); whereClass = whereClass.getSuperclass()) {
			toTable = database.getTable(whereClass);

			c = selectClass;
			for (; s == null && c != null && !(c.equals(OAObject.class)); c = c.getSuperclass()) {
				Table fromTable = database.getTable(c);
				if (fromTable == null) {
					continue;
				}
				Link[] links = fromTable.getLinks();
				for (int i = 0; s == null && links != null && i < links.length; i++) {
					if (links[i].toTable.bLink) {
						s = getWhere(	links[i].toTable.getLinks(), links[i].toTable, toTable, whereObject, propertyFromWhereObject,
										vecParam);
						bUseExists = false; // dont use EXISTS, use JOIN instead
					}
				}
			}
		}
		if (s == null) {
			String test = "selectClass=" + selectClass.getName() + " whereObject=" + whereObject + " propertyFromWhereObject="
					+ propertyFromWhereObject;
			throw new RuntimeException("QueryConverter.convertToWhere() Can not find links between objects/database: " + test);
		}
		return s;
	}

	/**
	 * Attempts to locate a matching link between fromTable and toTable and constructs
	 * a WHERE expression comparing foreign-key values against the whereObject's key.
	 *
	 * @param links                   links originating from the fromTable
	 * @param fromTable               table serving as source of the link
	 * @param toTable                 destination table expected in the link
	 * @param whereObject             object providing key values
	 * @param propertyFromWhereObject expected reverse property name for validation
	 * @param vecParams               vector receiving parameter values
	 * @return WHERE clause fragment or null if link not applicable
	 */
	private String getWhere(Link[] links, Table fromTable, Table toTable, Object whereObject, String propertyFromWhereObject,
			Vector vecParams) {
		// these are used to see if the object can be found without using select
		bEmpty = false;
		selectObject = null;

		for (int i = 0; links != null && i < links.length; i++) {
			Link link = links[i];
			Table newFromTable = null;
			if (link.toTable != toTable) {
				if (link.toTable == null) {
					continue;
				}
				if (!link.toTable.bLink) {
					continue;
				}

				if (propertyFromWhereObject == null) {
					continue;
				}
				if (!propertyFromWhereObject.equalsIgnoreCase(link.reversePropertyName)) {
					continue;
				}

				// check link table links
				Link[] linx = link.toTable.getLinks(); // will have 2 links
				if (linx == null || linx.length != 2) {
					continue;
				}

				newFromTable = link.toTable; // will need to be changed if it is using link table
				if (linx[0].toTable == toTable && propertyFromWhereObject.equalsIgnoreCase(linx[0].reversePropertyName)) {
					link = linx[0];
				} else {
					if (linx[1].toTable == toTable && propertyFromWhereObject.equalsIgnoreCase(linx[1].reversePropertyName)) {
						link = linx[1];
					} else {
						continue;
					}
				}
			}

			if (propertyFromWhereObject != null && propertyFromWhereObject.length() > 0) {
				if (!propertyFromWhereObject.equalsIgnoreCase(link.reversePropertyName)) {
					continue;
				}
			}

			if (newFromTable != null) {
				fromTable = newFromTable;
			}

			Column[] fkeys = link.fkeys;

			Column[] toFkeys = fromTable.getLinkToColumns(link, link.toTable);
			if (fkeys == null || toFkeys == null || fkeys.length != toFkeys.length) {
				throw new RuntimeException("different number of links between table " + fromTable.name + " and " + toTable.name);
			}
			String s = null;

			OAObjectKey key;
			if (toFkeys[0].primaryKey) {
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph((OAObject) whereObject);
				key = og.objectsInternal().callObjectKeyGetKey((OAObject) whereObject);
			} else {
				// 20090621
				OAGraphInternal og = (OAGraphInternal) OARuntime.graph((OAObject) whereObject);
				Object obj = og.objectsInternal().callObjectReflectGetRawReference((OAObject) whereObject, propertyFromWhereObject);
				if (obj instanceof OAObjectKey) {
					key = (OAObjectKey) obj;
				} else {
					if (obj instanceof OAObject) {
						og = (OAGraphInternal) OARuntime.graph((OAObject) obj);
						key = og.objectsInternal().callObjectKeyGetKey((OAObject) obj);
					} else {
						key = null;
					}
				}

				/* was:  bug: this could cause a inf loop for one2one links
				Object obj = ((OAObject)whereObject).getProperty(propertyFromWhereObject);
				key = OAObjectKeyDelegate.getKey((OAObject) obj);
				*/
			}

			Object[] ids;
			if (key != null) {
				ids = key.getObjectIds();
			} else {
				ids = null;
			}

			for (int j = 0; fkeys != null && j < fkeys.length; j++) {
				if (s == null) {
					s = "";
				} else {
					s += " AND ";
				}

				if (fromTable.bLink) {
					// 2003/05/10
					s += links[i].propertyName + '.' + toFkeys[j].propertyName;
					if (ids == null || ids.length <= j || ids[j] == null) {
						s += " == NULL";
					} else {
						s += " == ?";
						vecParams.add(ids[j]);
					}
				} else {
					s += fkeys[j].columnName;
					if (ids == null || ids.length <= j || ids[j] == null) {
						s += " == NULL";
					} else {
						s += " == ?";
						vecParams.add(ids[j]);
					}
				}
			}
			return s;
		}
		return null; // not found
	}

	/**
	 * Legacy version of getWhere(), performing similar link resolution and WHERE
	 * clause construction using earlier logic.
	 *
	 * @param links                   links from the source table
	 * @param fromTable               the source table
	 * @param toTable                 target table whose keys must match
	 * @param whereObject             object supplying key values
	 * @param propertyFromWhereObject link name from whereObject
	 * @param vecParams               vector receiving parameter values
	 * @return WHERE clause fragment or null if no matching link exists
	 */
	private String getWhere_ORIG(Link[] links, Table fromTable, Table toTable, Object whereObject, String propertyFromWhereObject,
			Vector vecParams) {
		// these are used to see if the object can be found without using select
		bEmpty = false;
		selectObject = null;

		for (int i = 0; links != null && i < links.length; i++) {
			if (links[i].toTable != toTable) {
				continue;
			}

			if (propertyFromWhereObject != null && propertyFromWhereObject.length() > 0) {
				if (!propertyFromWhereObject.equalsIgnoreCase(links[i].reversePropertyName)) {
					continue;
				}
			}
			Column[] fkeys = links[i].fkeys;

			Column[] toFkeys = fromTable.getLinkToColumns(links[i], toTable);
			if (fkeys == null || toFkeys == null || fkeys.length != toFkeys.length) {
				throw new RuntimeException("different number of links between table " + fromTable.name + " and " + toTable.name);
			}
			String s = null;

			OAObjectKey key;
			if (toFkeys[0].primaryKey) {
				final OAGraphInternal og = (OAGraphInternal) OARuntime.graph((OAObject) whereObject);
				key = og.objectsInternal().callObjectKeyGetKey((OAObject) whereObject);
			} else {
				// 20090621
				OAGraphInternal og = (OAGraphInternal) OARuntime.graph((OAObject) whereObject);
				Object obj = og.objectsInternal().callObjectReflectGetRawReference((OAObject) whereObject, propertyFromWhereObject);
				if (obj instanceof OAObjectKey) {
					key = (OAObjectKey) obj;
				} else {
					if (obj instanceof OAObject) {
						og = (OAGraphInternal) OARuntime.graph((OAObject) obj);
						key = og.objectsInternal().callObjectKeyGetKey((OAObject) obj);
					} else {
						key = null;
					}
				}

				/* was:  bug: this could cause a inf loop for one2one links
				Object obj = ((OAObject)whereObject).getProperty(propertyFromWhereObject);
				key = OAObjectKeyDelegate.getKey((OAObject) obj);
				*/
			}

			Object[] ids;
			if (key != null) {
				ids = key.getObjectIds();
			} else {
				ids = null;
			}

			for (int j = 0; fkeys != null && j < fkeys.length; j++) {
				if (s == null) {
					s = "";
				} else {
					s += " AND ";
				}

				if (fromTable.bLink) {
					// 2003/05/10
					s += links[i].propertyName + '.' + toFkeys[j].propertyName;
					if (ids == null || ids.length <= j || ids[j] == null) {
						s += " == NULL";
					} else {
						s += " == ?";
						vecParams.add(ids[j]);
					}
				} else {
					s += fkeys[j].columnName;
					if (ids == null || ids.length <= j || ids[j] == null) {
						s += " == NULL";
					} else {
						s += " == ?";
						vecParams.add(ids[j]);
					}
				}
			}
			return s;
		}
		return null; // not found
	}

	/**
	 * Produces FROM/WHERE/ORDER BY SQL text for the given class, without resolving
	 * subclass participation.
	 *
	 * @param clazz   root class for table resolution
	 * @param where   WHERE clause text
	 * @param params  parameter list, stored for later retrieval
	 * @param orderBy ORDER BY clause text
	 * @return FROM/WHERE/ORDER BY SQL fragment
	 */
	public String convertToSql(Class clazz, String where, Object[] params, String orderBy) {
		return convertToSql(clazz, where, params, orderBy, true);
	}

	/**
	 * Produces SQL using join resolution, optionally including subclass tables.
	 *
	 * @param clazz          root class for selecting table information
	 * @param where          WHERE clause text
	 * @param params         argument list
	 * @param orderBy        ORDER BY clause
	 * @param bGetSubclasses true to include subclass tables in join generation
	 * @return SQL fragment including FROM, WHERE, ORDER BY
	 */
	protected String convertToSql(Class clazz, String where, Object[] params, String orderBy, boolean bGetSubclasses) {
		return convertToSql(clazz, where, params, orderBy, bGetSubclasses, false);
	}

	/**
	 * Core SQL construction routine that sets up root Linkinfo, configures join
	 * usage, resolves WHERE logic (JOIN vs. EXISTS mode), and assembles the final
	 * FROM/WHERE/ORDER BY SQL text.
	 *
	 * @param clazz                   root selection class
	 * @param where                   WHERE clause text
	 * @param params                  argument array
	 * @param orderBy                 ORDER BY clause
	 * @param bGetSubclasses          whether subclasses participate in joins
	 * @param bUsingPreparedStatement true when '?' placeholders should be used
	 * @return SQL fragment combining joins and filtering
	 */
	protected String convertToSql(Class clazz, String where, Object[] params, String orderBy, boolean bGetSubclasses,
			boolean bUsingPreparedStatement) {

		reset();
		root = new Linkinfo(clazz); // this will load super and sub classes
		this.arguments = params;

		// make sure all root are flagged to be used and "LEFT JOINED"
		root.bUsed = true;

		Linkinfo li = root;
		for (;;) {
			li = li.superLink;
			if (li == null) {
				break;
			}
			li.bUsed = true;
			li.bUseLeftJoin = true;
		}
		for (int i = 0; root.subLinks != null && i < root.subLinks.length; i++) {
			root.subLinks[i].bUsed = true;
			root.subLinks[i].bUseLeftJoin = true;
		}

		// orderBy clause
		if (orderBy != null && orderBy.length() > 0) {
			orderBy = parseOrderBy(clazz, orderBy);
			orderBy = " ORDER BY " + orderBy; // "ORDER BY" must be in caps, since OADataSourceJDBC might look for it
		}

		// create where clause
		if (where != null && where.length() > 0) {
			if (bUseExists) {
				where = parseWhereUseExist(clazz, where, params, bUsingPreparedStatement);
			} else {
				where = parseWhereUseJoin(clazz, where, params, bUsingPreparedStatement);
			}
		}

		extraWhere = "";
		String joins = getJoins();
		if (extraWhere != null && extraWhere.length() > 0) {
			where = extraWhere + ((where != null && where.length() > 0) ? (" AND (" + where + ")") : "");
		}

		if (where != null && where.length() > 0) {
			where = " WHERE " + where;
		} else {
			where = "";
		}

		String s = "FROM " + joins + where + ((orderBy != null) ? orderBy : "");
		return s;
	}

	/**
	 * Parses an ORDER BY expression containing property paths, resolves each to the
	 * correct table/column, and returns an equivalent SQL ORDER BY clause.
	 *
	 * @param clazz root class for property resolution
	 * @param line  textual ORDER BY expression
	 * @return transformed ORDER BY SQL clause
	 */
	protected String parseOrderBy(Class clazz, String line) {
		if (line == null) {
			return null;
		}

		Table table = database.getTable(clazz);
		String tableName = table.name.toUpperCase();

		String newString = "";
		boolean bAllowDesc = false;
		StringTokenizer st = new StringTokenizer(line, " ,", true);
		for (; st.hasMoreTokens();) {
			String s = st.nextToken();
			if (s.equals(",")) {
				bAllowDesc = false;
			} else if (s.equals(" ")) {
			} else {
				if (bAllowDesc && s.equalsIgnoreCase("desc")) {
					bAllowDesc = false;
				} else {
					// 2006/09/13
					String funcName = null;
					int pos = s.indexOf('(');
					if (pos >= 0) {
						funcName = s.substring(0, pos);
						s = s.substring(pos + 1);
						pos = s.indexOf(')');
						if (pos >= 0) {
							s = s.substring(0, pos);
						}
					}

					Vector vecLink = new Vector(5, 5);
					Column[] columns = parseLink(vecLink, s); // populates vecLink with Linkinfos to match path in token.value
					if (columns == null) {
						throw new RuntimeException(
								"Cant use find column in ORDER clause: property \"" + s + "\" in query \"" + line + "\"");
					}
					int xx = vecLink.size();
					for (int ii = 1; ii < xx; ii++) {
						Linkinfo li = (Linkinfo) vecLink.elementAt(ii);
						li.bUsed = true;
						li.bUseLeftJoin = true;
						if (li.base != null) {
							li.base.bUsed = true;
							li.bUseLeftJoin = true;
						}
						if (li.bMany) {
							throw new RuntimeException(
									"Cant use link with MANY property in ORDER clause: property \"" + s + "\" in query \"" + line + "\"");
						}
					}
					Linkinfo li = (Linkinfo) vecLink.elementAt(xx - 1);

					s = "";
					for (Column column : columns) {
						String colName;
						if (dbmd.caseSensitive && column.type == java.sql.Types.VARCHAR && !column.primaryKey) {
							colName = column.columnLowerName;
							if (colName != null && colName.trim().length() > 0 && !colName.equalsIgnoreCase(column.columnName)) {
							} else {
								colName = column.columnName;
								funcName = dbmd.lowerCaseFunction;
							}
						} else {
							colName = column.columnName;
						}

						if (s.length() > 0) {
							s += ", ";
						}
						s += (funcName == null ? "" : (funcName + "(")) + LB + li.table.name.toUpperCase()
								+ (li.number > 0 ? li.number + "" : "") + RB + "." + LB + colName.toUpperCase() + RB
								+ (funcName == null ? "" : ")");
					}
					if (columns.length > 1) {
						s = "(" + s + ")";
					}

					bAllowDesc = true;
				}
			}
			newString += s;
		}
		return newString;
	}

	/**
	 * Converts a WHERE clause that relies on JOIN-based navigation, resolving column
	 * paths, applying case-sensitivity rules, handling NULL semantics, and converting
	 * parameters to SQL text or '?' placeholders.
	 *
	 * @param clazz                   class used for base table resolution
	 * @param whereClause             textual WHERE clause
	 * @param params                  parameter list
	 * @param bUsingPreparedStatement true when '?' placeholders should be used
	 * @return SQL WHERE clause
	 */
	protected String parseWhereUseJoin(Class clazz, String whereClause, Object[] params, boolean bUsingPreparedStatement) {
		OAQueryTokenizer qa = new OAQueryTokenizer();
		Vector vecToken = qa.convertToTokens(whereClause);
		cleanTokens(vecToken, params);

		// 20100821 if where column in joined table uses "is null", then need to also
		//  add "is not null" to "id" column, to offset records that dont exist
		//  ex: pet.exam.examitems.endDate = null - would return all with endDate=null and where no examItems exists for the pet

		int[] nullColumns = new int[0];

		int x = vecToken.size();
		for (int i = 1; i < (x - 1); i++) {
			OAQueryToken token = (OAQueryToken) vecToken.elementAt(i);
			if (!"IS".equals(token.value)) {
				continue;
			}
			token = (OAQueryToken) vecToken.elementAt(i + 1);
			if (!"NULL".equals(token.value)) {
				continue;
			}

			token = (OAQueryToken) vecToken.elementAt(i - 1);
			if (token.value.indexOf('.') < 0) {
				continue;
			}

			int colPos = (i - 1);
			nullColumns = OAArray.add(nullColumns, colPos + 1);

			// found one
			// need to add "(" and add "AND" for "is not null" column

			// (
			OAQueryToken t = new OAQueryToken();
			t.value = "(";
			t.type = OAQueryTokenType.SEPERATORBEGIN;
			vecToken.insertElementAt(t, colPos);

			// AND
			t = new OAQueryToken();
			t.type = OAQueryTokenType.AND;
			t.value = "AND";
			i += 3;
			vecToken.insertElementAt(t, i++);

			// COLUMN.PKEY
			token = (OAQueryToken) vecToken.elementAt(colPos + 1);
			t = new OAQueryToken();
			t.type = token.type;
			t.value = token.value;
			vecToken.insertElementAt(t, i++);

			// NOTEQUAL
			t = new OAQueryToken();
			t.type = OAQueryTokenType.NOTEQUAL;
			t.value = "IS NOT";
			vecToken.insertElementAt(t, i++);

			// NULL
			t = new OAQueryToken();
			t.type = OAQueryTokenType.NULL;
			t.value = "NULL";
			vecToken.insertElementAt(t, i++);

			// )
			t = new OAQueryToken();
			t.type = OAQueryTokenType.SEPERATOREND;
			t.value = ")";
			vecToken.insertElementAt(t, i++);
		}

		String where = "";
		Table table = null;
		Column[] columns = null;
		Column origColumn = null;
		String fullTextIndex = null;
		boolean bLastColumnWasInFunction = false; // 20121120
		int paramPos = 0;
		x = vecToken.size();
		for (int i = 0; i < x; i++) {
			OAQueryToken token = (OAQueryToken) vecToken.elementAt(i);
			String s = null;

			OAQueryToken tokenNext;
			if (i + 1 != x) {
				tokenNext = (OAQueryToken) vecToken.elementAt(i + 1);
			} else {
				tokenNext = null;
			}

			if (token.type == OAQueryTokenType.VARIABLE && tokenNext != null && tokenNext.type == OAQueryTokenType.FUNCTIONBEGIN) {
				s = token.value;
			} else if (token.type == OAQueryTokenType.VARIABLE) {
				Vector vecLink = new Vector(5, 5);
				columns = parseLink(vecLink, token.value); // populates vecLink with Linkinfos to match path in token.value

				s = "";
				int columnsPos = -1;
				for (Column column : columns) {
					columnsPos++;
					if (OAArray.contains(nullColumns, i)) {
						OAQueryToken t = (OAQueryToken) vecToken.elementAt(i + 4);
						boolean b = false;
						for (Column c : column.table.getColumns()) {
							if (c.primaryKey) {
								int pos = t.value.lastIndexOf('.');
								t.value = t.value.substring(0, pos);
								t.value += "." + c.propertyName;
								b = true;
								break;
							}
						}
						if (!b) {
							// todo: this needs to have a warning, that there is no other field to check for non-null value
							//   for now, set it back to the same "IS NULL"
							t = (OAQueryToken) vecToken.elementAt(i + 5);
							t.type = OAQueryToken.EQUAL;
						}

					}

					if (column == null) {
						throw new RuntimeException(
								"Cant use find column in WHERE clause: property \"" + token.value + "\" in query \"" + whereClause + "\"");
					}
					origColumn = column;
					int xx = vecLink.size();

					// if a pkey column is being used, see if fkey can be used instead
					if (xx > 1 && column != null && column.primaryKey && columns.length == 1) {
						final Linkinfo liLast = vecLink.size() == 0 ? null : (Linkinfo) vecLink.elementAt(vecLink.size() - 1);
						if (!liLast.bMany) {
							column = liLast.linkFromParent.fkeys[columnsPos];
							vecLink.removeElementAt(--xx);
						}
					}

					for (int ii = 1; ii < xx; ii++) {
						Linkinfo li = (Linkinfo) vecLink.elementAt(ii);
						li.bUsed = true;
						li.bUseLeftJoin = true;
						if (li.base != null) {
							li.base.bUsed = true;
							li.bUseLeftJoin = true;
						}
						if (li.bMany) {
							bUseDistinct = true; // need to use "<DISTINCT KEYWORD>" with select
						}
					}
					Linkinfo li = (Linkinfo) vecLink.elementAt(xx - 1);

					// 2006/09/22 check for using case sensitive searches.
					String colName;

					// 20151206 check for fulltextindex
					if (column.fullTextIndex && (dbmd.getDatabaseType() == DBMetaData.SQLSERVER)) {
						colName = column.columnLowerName;
						colName = LB + li.table.name.toUpperCase() + (li.number > 0 ? li.number + "" : "") + RB + "." + LB + colName + RB;
						fullTextIndex = colName;
						colName = null;
					} else if (dbmd.caseSensitive && column.type == java.sql.Types.VARCHAR && !column.primaryKey) {
						colName = column.columnLowerName;
						if (colName != null && colName.trim().length() > 0 && !colName.equalsIgnoreCase(column.columnName)) {
							colName = colName.toUpperCase();
							colName = LB + li.table.name.toUpperCase() + (li.number > 0 ? li.number + "" : "") + RB + "." + LB + colName
									+ RB;
						} else {
							colName = column.columnName.toUpperCase();
							colName = LB + li.table.name.toUpperCase() + (li.number > 0 ? li.number + "" : "") + RB + "." + LB + colName
									+ RB;
							colName = dbmd.lowerCaseFunction + "(" + colName + ")";
						}

						// need to make search string is lower case
						if (i + 1 < x) {
							OAQueryToken t = (OAQueryToken) vecToken.elementAt(i + 1);
							if (t.isOperator()) {
								// find next var and make lowercase  2006/09/22
								for (int j = i + 2; j < x; j++) {
									t = (OAQueryToken) vecToken.elementAt(j);
									if (t.type == OAQueryTokenType.STRINGSQ || t.type == OAQueryTokenType.STRINGDQ) {
										t.value = t.value.toLowerCase();
										break;
									}
									if (t.type == OAQueryTokenType.QUESTION) {
										if (params != null && paramPos < params.length && params[paramPos] instanceof String) {
											params[paramPos] = ((String) params[paramPos]).toLowerCase();
										}
										break;
									}
								}
							}
						}
					} else {
						colName = column.columnName;
						if (colName != null) {
							colName = colName.toUpperCase();
						}
						colName = LB + li.table.name.toUpperCase() + (li.number > 0 ? li.number + "" : "") + RB + "." + LB + colName + RB;
					}
					if (s.length() > 0) {
						s += ", ";
					}
					s += colName;
				}
				if (s.indexOf(", ") >= 0) {
					s = "(" + s + ")";
				}
			} else if (token.type == OAQueryTokenType.QUESTION) {
				if (params == null || paramPos >= params.length) {
					throw new RuntimeException("wrong number of params in query");
				}
				if (origColumn == null) {
					throw new RuntimeException("column not found for parameter");
				}
				Object param = params[paramPos++];
				if (origColumn != columns[columns.length - 1]) {
					if (param instanceof OAObject) {
						param = ((OAObject) param).getProperty(origColumn.propertyName);
					}
				}

				// 20121013
				if (fullTextIndex == null && bUsingPreparedStatement) {
					s = "?";
				} else {
					if (bLastColumnWasInFunction || param instanceof List) {
						bLastColumnWasInFunction = false;
						if (param instanceof String) {
							s = ConverterDelegate.convert(dbmd, origColumn, param);
						} else {
							s = ConverterDelegate.convert(dbmd, null, param);
						}
					} else {
						s = ConverterDelegate.convert(dbmd, origColumn, param);
					}
				}

				if (fullTextIndex != null) {
					s = "CONTAINS(" + fullTextIndex + ", '" + s + "')";
					fullTextIndex = null;
				}
			} else if (token.type == OAQueryTokenType.STRINGSQ || token.type == OAQueryTokenType.STRINGDQ) {
				if (columns == null || columns.length == 0) {
					throw new RuntimeException("column not found for parameter");
				}
				s = ConverterDelegate.convert(dbmd, columns[0], token.value);
				if (fullTextIndex != null) {
					s = "CONTAINS(" + fullTextIndex + ", '" + s + "')";
					fullTextIndex = null;
				}
			} else if (token.type == OAQueryTokenType.NUMBER) {
				if (columns == null || columns.length == 0) {
					throw new RuntimeException("column not found for parameter");
				}
				s = ConverterDelegate.convert(dbmd, columns[0], token.value);
				if (fullTextIndex != null) {
					s = "CONTAINS(" + fullTextIndex + ", '" + s + "')";
					fullTextIndex = null;
				}
			} else if (token.type == OAQueryTokenType.FUNCTIONBEGIN) {
				bLastColumnWasInFunction = true;
				s = token.value;
			} else if (token.type == OAQueryTokenType.LIKE) {
				s = dbmd.getLikeKeyword();
				if (OAString.isEmpty(s)) {
					s = token.value;
				}
			} else if (token.type == OAQueryTokenType.NOTLIKE) {
				s = dbmd.getLikeKeyword();
				if (OAString.isEmpty(s)) {
					s = token.value;
				} else {
					s = "not " + s;
				}
			} else if (token.type == OAQueryTokenType.TRUE) {
				if (dbmd.objectTrue != null) {
					s = "" + dbmd.objectTrue;
				}
			} else if (token.type == OAQueryTokenType.FALSE) {
				if (dbmd.objectFalse != null) {
					s = "" + dbmd.objectFalse;
				}
			} else {
				s = token.value;
				if (fullTextIndex != null) {
					s = null;
				}
			}

			if (s != null && s.length() > 0) {
				if (where.length() > 0) {
					where += " ";
				}
				where += s;
			}
		}
		if (where.indexOf(" NULL") >= 0) {
			where = OAString.convert(where, " = NULL", " IS NULL");
			where = OAString.convert(where, " != NULL", " IS NOT NULL");
			where = OAString.convert(where, " <> NULL", " IS NOT NULL");
		}
		return where;
	}

	/**
	 * Converts a WHERE clause into EXISTS-based SQL, used for navigating many-side
	 * relationships. Builds nested EXISTS statements and resolves column paths.
	 *
	 * @param clazz                   root class for resolving table links
	 * @param whereClause             textual WHERE clause
	 * @param params                  parameter list
	 * @param bUsingPreparedStatement true when '?' placeholders should be used
	 * @return SQL WHERE clause using EXISTS semantics
	 */
	protected String parseWhereUseExist(Class clazz, String whereClause, Object[] params, boolean bUsingPreparedStatement) {
		OAQueryTokenizer qa = new OAQueryTokenizer();
		Vector vecToken = qa.convertToTokens(whereClause);
		cleanTokens(vecToken, params);

		int existCount = 0;
		Vector vecLink = new Vector(5, 5);
		Vector vecPreLink = null;

		String where = "";
		OAQueryToken holdToken = null;
		Column[] columns = null;
		Column origColumn = null;
		int paramPos = 0;

		int x = vecToken.size();
		boolean bLastColumnWasInFunction = false; // 20121120
		for (int i = 0; i < x; i++) {
			OAQueryToken token = (OAQueryToken) vecToken.elementAt(i);

			OAQueryToken tokenNext;
			if (i + 1 != x) {
				tokenNext = (OAQueryToken) vecToken.elementAt(i + 1);
			} else {
				tokenNext = null;
			}

			String newVar = "";

			if (token.type == OAQueryTokenType.VARIABLE && tokenNext != null && tokenNext.type == OAQueryTokenType.FUNCTIONBEGIN) {
				//20090608 todo, have Datasource convert function name to correct name for the database that is being used.
				newVar = token.value;
				if (holdToken != null) {
					newVar = holdToken.value + " " + newVar;
					holdToken = null;
				}
			} else if (token.type == OAQueryTokenType.VARIABLE) {
				vecPreLink = (Vector) vecLink.clone();
				vecLink.removeAllElements();

				columns = parseLink(vecLink, token.value); // populates vecLink with Linkinfos to match path

				int columnPos = -1;
				for (Column column : columns) {
					columnPos++;
					origColumn = column;
					int xx = vecLink.size();

					// if a pkey column is being used, see if fkey can be used instead
					if (xx > 1 && column != null && column.primaryKey && columns.length == 1) {
						Linkinfo li = (Linkinfo) vecLink.elementAt(xx - 1);
						if (!li.bMany) {
							// use fkey from previous Linkinfo
							column = li.linkFromParent.fkeys[0];
							li = (Linkinfo) vecLink.elementAt(xx - 2);
							xx--;
							vecLink.removeElementAt(xx);
						}
					}

					// see if path is same as prev path
					if (vecPreLink != null && columnPos == 0) {
						int xxx = vecPreLink.size();
						if (xxx > 0 && xxx <= xx && column != null) {
							for (int j = 0; j < xxx; j++) {
								if (vecPreLink.elementAt(j) != vecLink.elementAt(j)) {
									vecPreLink = null;
									break;
								}
							}
							for (int j = xxx; vecPreLink != null && j < xx; j++) {
								Linkinfo li = (Linkinfo) vecLink.elementAt(j);
								if (!li.bMany) {
									vecPreLink = null; // cant add JOINS to previous statement, only EXISTS
								}
							}
						} else {
							vecPreLink = null;
						}
					}

					if (vecPreLink == null && columnPos == 0) {
						// close previous
						for (int j = 0; j < existCount; j++) {
							where += ")";
						}
						existCount = 0;
					}

					if (holdToken != null) {
						if (where.length() > 0) {
							where += " ";
						}
						where += holdToken.value;
						holdToken = null;
					}

					// set up variable name
					Linkinfo li = ((xx == 0) ? root : (Linkinfo) vecLink.elementAt(0));

					// check for MANY relationships within property path
					Linkinfo prev;
					Linkinfo liMany = null;
					String exists = "";
					for (int ii = 1; ii < xx; ii++) {
						prev = li;
						li = (Linkinfo) vecLink.elementAt(ii);
						if (!li.bMany) {
							li.bUsed = true;
							if (li.base != null) {
								li.base.bUsed = true;
							}

							if (bUsingOR && ii == 1) {
								bUseLeftJoin = true;
							}
							continue;
						}
						if (vecPreLink != null) {
							if (ii < vecPreLink.size() && (li == (Linkinfo) vecPreLink.elementAt(ii))) {
								continue; // exists/joins already built from last vecLink
							}
						}

						// Many
						if (liMany != null) {
							// need to include JOINs for any ONE relationships that might have been in path
							extraWhere = ""; // built by getJoins for Oracle
							boolean bHold = bUseLeftJoin;
							bUseLeftJoin = false;
							newVar += "EXISTS (SELECT * FROM " + getJoins(vecLink, liMany, prev) + " WHERE " + exists + " AND ";
							bUseLeftJoin = bHold;
							if (extraWhere != null && extraWhere.length() > 0) {
								newVar += extraWhere + " AND ";
							}
							existCount++;
						}

						for (int ix = 0; ix < li.linkFromParent.fkeys.length; ix++) {
							if (ix > 0) {
								exists += " AND ";
							}
							exists = LB + prev.table.name.toUpperCase() + (prev.number > 0 ? prev.number + "" : "") + RB;
							exists += ".";
							exists += LB + li.linkFromParent.fkeys[ix].columnName + RB;

							exists += " = ";

							Linkinfo lix = (li.base == null) ? li : li.base; // base link is the "real" link to the parent link
							exists += LB + lix.table.name.toUpperCase() + (lix.number > 0 ? li.number + "" : "") + RB;
							exists += ".";
							exists += LB + lix.linkToParent.fkeys[ix].columnName + RB;
						}

						liMany = li;
					}

					if (liMany != null) {
						if (column == null) {
							// ex: orders.orderItems = null
							//     orders.orderItems != null
							// need to look ahead

							OAQueryToken t = null;
							if (i + 1 < x) {
								t = (OAQueryToken) vecToken.elementAt(i + 1);
							}
							if (t != null && t.type == OAQueryTokenType.EQUAL) {
								newVar += "NOT ";
								vecLink.removeAllElements(); // so that it can not be shared with next path
							}
						}
						extraWhere = ""; // built by getJoins for Oracle
						boolean bHold = bUseLeftJoin;
						bUseLeftJoin = false;
						newVar += "EXISTS (SELECT * FROM " + getJoins(vecLink, liMany, null) + " WHERE " + exists;
						bUseLeftJoin = bHold;
						if (extraWhere != null && extraWhere.length() > 0) {
							newVar += " AND " + extraWhere;
						}
						if (column != null) {
							newVar += " AND ";
						}
						existCount++;
					}

					if (column != null) {
						// 2006/09/22 check for using case sensitve searches.
						String colName;
						if (dbmd.caseSensitive && column.type == java.sql.Types.VARCHAR && !column.primaryKey) {
							colName = column.columnLowerName;
							if (colName != null && colName.trim().length() > 0 && !colName.equalsIgnoreCase(column.columnName)) {
								colName = colName.toUpperCase();
								colName = LB + li.table.name.toUpperCase() + (li.number > 0 ? li.number + "" : "") + RB + "." + LB + colName
										+ RB;
							} else {
								colName = column.columnName.toUpperCase();
								colName = LB + li.table.name.toUpperCase() + (li.number > 0 ? li.number + "" : "") + RB + "." + LB + colName
										+ RB;
								colName = dbmd.lowerCaseFunction + "(" + colName + ")";
							}

							// need to make search string is lower case
							if (i + 1 < x) {
								OAQueryToken t = (OAQueryToken) vecToken.elementAt(i + 1);
								if (t.isOperator()) {
									// find next var and make lowercase  2006/09/22
									for (int j = i + 2; j < x; j++) {
										t = (OAQueryToken) vecToken.elementAt(j);
										if (t.type == OAQueryTokenType.STRINGSQ || t.type == OAQueryTokenType.STRINGDQ) {
											t.value = t.value.toLowerCase();
											break;
										}
										if (t.type == OAQueryTokenType.QUESTION) {
											if (params != null && paramPos < params.length && params[paramPos] instanceof String) {
												params[paramPos] = ((String) params[paramPos]).toLowerCase();
											}
											break;
										}
									}
								}
							}
						} else {
							colName = column.columnName;
							if (colName != null) {
								colName = colName.toUpperCase();
							}
							colName = LB + li.table.name.toUpperCase() + (li.number > 0 ? li.number + "" : "") + RB + "." + LB + colName
									+ RB;
						}
						if (newVar.length() > 0 && columnPos > 0) {
							newVar += ", ";
						}
						newVar += colName;
					} else {
						// ignore "= null" or "!= null"
						for (int j = 0; j < existCount; j++) {
							newVar += ")";
						}
						OAQueryToken t = null;
						if (i + 1 < x) {
							t = (OAQueryToken) vecToken.elementAt(i + 1);
						}
						if (t != null && t.isOperator()) {
							i += 2;
						}

						vecLink.removeAllElements(); // so that it can not be shared with next path
						existCount = 0;
					}
				}
				if (columns.length > 1) {
					newVar = "(" + newVar + ")";
				}
			} else if (token.type == OAQueryTokenType.QUESTION) {
				if (params == null || paramPos >= params.length) {
					throw new RuntimeException("wrong number of params in query");
				}
				if (origColumn == null) {
					throw new RuntimeException("column not found for parameter");
				}
				Object param = params[paramPos++];
				if (origColumn != columns[0] && columns.length == 1) {
					if (param instanceof OAObject) {
						param = ((OAObject) param).getProperty(origColumn.propertyName);
						params[paramPos - 1] = param;
					}
				}

				// 20130113
				if (bUsingPreparedStatement) {
					newVar = "?";
				} else {
					if (bLastColumnWasInFunction || param instanceof List) {
						bLastColumnWasInFunction = false;
						if (param instanceof String) {
							newVar = ConverterDelegate.convert(dbmd, origColumn, param);
						} else {
							newVar = ConverterDelegate.convert(dbmd, null, param);
						}
					} else {
						newVar = ConverterDelegate.convert(dbmd, origColumn, param);
					}
				}
			} else if (token.type == OAQueryTokenType.STRINGSQ || token.type == OAQueryTokenType.STRINGDQ) {
				if (columns == null || columns.length == 0) {
					throw new RuntimeException("column not found for parameter");
				}
				newVar = ConverterDelegate.convert(dbmd, columns[0], token.value);
			} else if (token.type == OAQueryTokenType.NUMBER) {
				if (columns == null || columns.length == 0) {
					throw new RuntimeException("column not found for parameter");
				}
				newVar = ConverterDelegate.convert(dbmd, columns[0], token.value);
			} else if (token.type == OAQueryTokenType.FUNCTIONBEGIN) {
				bLastColumnWasInFunction = true;
				newVar = token.value;
			} else if (token.type == OAQueryTokenType.LIKE) {
				newVar = dbmd.getLikeKeyword();
				if (OAString.isEmpty(newVar)) {
					newVar = token.value;
				}
			} else if (token.type == OAQueryTokenType.NOTLIKE) {
				newVar = dbmd.getLikeKeyword();
				if (OAString.isEmpty(newVar)) {
					newVar = token.value;
				} else {
					newVar = "not " + newVar;
				}
			} else {
				newVar = token.value;
				if (token.type == OAQueryTokenType.OR || token.type == OAQueryTokenType.AND) {
					if (token.type == OAQueryTokenType.OR) {
						bUsingOR = true;
					}
					holdToken = token;
					newVar = null;
				} else if (token.type == OAQueryTokenType.SEPERATORBEGIN) {
					for (int j = 0; j < existCount; j++) {
						where += ")";
					}
					existCount = 0;

					if (holdToken != null) {
						newVar = holdToken.value + " " + newVar;
						vecLink.removeAllElements(); // so that it can not be shared with next path
						holdToken = null;
					}
				} else if (token.type == OAQueryTokenType.TRUE) {
					if (dbmd.objectTrue != null) {
						Object obj = dbmd.objectTrue;
						if (dbmd.booleanKeyword && (obj instanceof String)) {
							newVar = (String) obj;
						} else {
							newVar = "'" + obj + "'";
						}
					} else {
						newVar = "1";
					}
				} else if (token.type == OAQueryTokenType.FALSE) {
					if (dbmd.objectFalse != null) {
						Object obj = dbmd.objectFalse;
						if (dbmd.booleanKeyword && (obj instanceof String)) {
							newVar = (String) obj;
						} else {
							newVar = "'" + obj + "'";
						}
					} else {
						newVar = "0";
					}
				}
			}

			if (newVar != null && newVar.length() > 0) {
				if (where.length() > 0) {
					where += " ";
				}
				where += newVar;
			}
		}

		for (int j = 0; j < existCount; j++) {
			where += ")";
		}

		return where;
	}

	/**
	 * Parses a dotted property path into a sequence of Linkinfo nodes and determines
	 * the final column(s) addressed by the path.
	 *
	 * @param vec  vector receiving Linkinfo objects along the property path
	 * @param line property path to parse
	 * @return array of resolved columns, or null when path terminates in a link
	 */
	protected Column[] parseLink(Vector vec, String line) {
		StringTokenizer st = new StringTokenizer(line, ".", true);

		Linkinfo linkinfo = root;

		for (;;) {
			String s = st.nextToken();
			if (s.equals(".")) {
				if (!st.hasMoreElements()) {
					return null; // bad path name
				}
				continue;
				/*
				if (i > 0) continue;
				s = "";  // might not have a property name if a "none (1 w/o method)" to many
				*/
			}

			if (!st.hasMoreElements()) {
				Column c = linkinfo.findColumn(vec, s);
				if (c != null) {
					return new Column[] { c };
				}
			}

			linkinfo = linkinfo.findLink(vec, s);
			if (linkinfo == null) {
				throw new RuntimeException("QueryConverter.parseLink() cant find link for \"" + s + "\", parse line=" + line);
			}

			if (!st.hasMoreElements()) {
				vec.addElement(linkinfo);

				// ex: dept.emps.orders = null
				// 20110415 removed this, so that it will use the pkey column
				//                if (linkinfo.bMany) return null;

				// ex: hubEmp.select("dept = null") ->  "emp.dept.id = NULL"
				Column[] pcols = linkinfo.table.getPrimaryKeyColumns();
				return pcols;
			}
		}
	}

	/**
	 * Normalizes query tokens by adjusting operators, merging adjacent quoted text,
	 * applying NULL-comparison rules, and updating parameters where needed.
	 *
	 * @param vec    token list produced by the tokenizer
	 * @param params parameter array associated with '?' tokens
	 */
	protected void cleanTokens(Vector vec, Object[] params) {
		OAQueryToken prevToken = null;
		int questionCnt = 0;
		int x = vec.size();
		for (int i = 0; i < x; i++) {
			OAQueryToken token = (OAQueryToken) vec.elementAt(i);
			switch (token.type) {
			case OAQueryTokenType.GT:
				break;
			case OAQueryTokenType.GE:
				break;
			case OAQueryTokenType.LT:
				break;
			case OAQueryTokenType.LE:
				break;
			case OAQueryTokenType.EQUAL:
				token.value = "=";
				break;
			case OAQueryTokenType.NOTEQUAL:
				token.value = "<>";
				break;
			case OAQueryTokenType.AND:
				token.value = "AND";
				break;
			case OAQueryTokenType.OR:
				token.value = "OR";
				break;
			case OAQueryTokenType.QUESTION:
				if (params == null || questionCnt >= params.length || params[questionCnt++] == null) {
					if (prevToken.type == OAQueryTokenType.EQUAL) {
						prevToken.value = "IS";
					} else if (prevToken.type == OAQueryTokenType.NOTEQUAL) {
						prevToken.value = "IS NOT";
					}
				}
				break;
			case OAQueryTokenType.NULL:
				token.value = "NULL";
				if (prevToken.type == OAQueryTokenType.EQUAL) {
					prevToken.value = "IS";
				} else if (prevToken.type == OAQueryTokenType.NOTEQUAL) {
					prevToken.value = "IS NOT";
				}
				break;
			case OAQueryTokenType.STRINGSQ:
				for (;;) {
					// see if next token is also a single quote
					if (i + 1 == x) {
						break;
					}
					OAQueryToken tok = (OAQueryToken) vec.elementAt(i + 1);
					if (tok.type != OAQueryTokenType.STRINGSQ) {
						break;
					}
					token.value += "''" + tok.value;
					vec.removeElementAt(i + 1);
					x--;
				}
				break;
			case OAQueryTokenType.STRINGDQ:
				break;
			case OAQueryTokenType.STRINGESC:
				token.value = "{" + token.value + "}";
				break;
			case OAQueryTokenType.NUMBER:
				break;
			case OAQueryTokenType.VARIABLE:
				if (token.value.equalsIgnoreCase("IS")) {
					if ((i + 1) != x) {
						OAQueryToken token2 = (OAQueryToken) vec.elementAt(i + 1);
						if (token2.type == OAQueryTokenType.NULL) {
							token.type = OAQueryTokenType.EQUAL;
						} else {
							if (token2.value.equalsIgnoreCase("NOT") && (i + 2) != x) {
								token2 = (OAQueryToken) vec.elementAt(i + 2);
								if (token2.type == OAQueryTokenType.NULL) {
									token.type = OAQueryTokenType.NOTEQUAL;
								}
							}
						}
					}
				} else if (token.value.equalsIgnoreCase("TRUE")) {
					token.type = OAQueryTokenType.TRUE;
				} else if (token.value.equalsIgnoreCase("FALSE")) {
					token.type = OAQueryTokenType.FALSE;
				}
				break;
			case OAQueryTokenType.EOF:
				break;
			default:
			}
			prevToken = token;
		}
	}

	/**
	 * Generates the full JOIN clause beginning at the root Linkinfo, including
	 * superclasses, subclasses, and relationship links.
	 *
	 * @return SQL fragment containing all required JOIN expressions
	 */
	protected String getJoins() {
		Table table = root.table;
		String s = LB + table.name.toUpperCase() + RB;
		s = getJoins(s, root, true, true);
		return s;
	}

	// strJoin = "(" + strJoin + " {oj LEFT OUTER JOIN " + toTable.name + " ON ";
	// {oj dept A left outer join emp B on A.deptNo = B.deptNo}
	/**
	 * Recursively builds JOIN expressions for superclass and subclass tables, as well
	 * as relationship links, starting from the specified Linkinfo node.
	 *
	 * @param strJoin current JOIN clause under construction
	 * @param li      link info node representing a table in the hierarchy
	 * @param bSuper  true to include superclass joins
	 * @param bSub    true to include subclass joins
	 * @return updated JOIN clause including all applicable tables
	 */
	private String getJoins(String strJoin, Linkinfo li, boolean bSuper, boolean bSub) {
		Table table = li.table;
		Column[] cols = table.getColumns();

		if (bSuper && li.superLink != null && !li.bMany) {
			if (li.superLink.bUsed) {

				// li might not be a used link, but a superLink above it can be.  If superLink is used, then need to
				//   join it with first subLink under it that is used
				// find used sub link closest to li.superLink
				Linkinfo lix = li;
				for (Linkinfo l = li.base; l != null && l != li.superLink; l = l.superLink) {
					if (l.bUsed) {
						lix = l;
					}
				}
				table = lix.table;
				cols = table.getColumns();

				String tname = LB + li.superLink.table.name.toUpperCase() + RB;
				if (li.superLink.number > 0) {
					tname += " " + LB + li.superLink.table.name.toUpperCase() + li.superLink.number + RB;
				}

				if (dbmd.databaseType == dbmd.ORACLE) {
					strJoin += "," + tname;
				} else {
					String s = "INNER JOIN";
					if (bUseLeftJoin || lix.bUseLeftJoin) {
						s = "LEFT OUTER JOIN"; // 2006/11/08
					}

					strJoin = "(" + dbmd.outerjoinStart + strJoin + " " + s + " " + tname + " ON ";
				}

				for (int i = 0, j = 0; i < cols.length; i++) {
					if (!cols[i].primaryKey) {
						continue;
					}

					if (dbmd.databaseType == dbmd.ORACLE && extraWhere.length() > 0) {
						extraWhere += " AND ";
					}
					if (j > 0 && dbmd.databaseType != dbmd.ORACLE) {
						strJoin += " AND ";
					}
					j++;
					tname = table.name.toUpperCase() + ((lix.number > 0) ? lix.number + "" : "");

					if (dbmd.databaseType == dbmd.ORACLE) {
						extraWhere += LB + tname + RB + "." + LB + cols[i].columnName.toUpperCase() + RB;
						extraWhere += " = ";
						tname = li.superLink.table.name.toUpperCase() + ((li.superLink.number > 0) ? li.superLink.number + "" : "");
						extraWhere += LB + tname + RB + "." + LB + cols[i].columnName.toUpperCase() + RB + "(+)";
					} else {
						strJoin += LB + tname + RB + "." + LB + cols[i].columnName.toUpperCase() + RB;
						strJoin += " = ";
						tname = li.superLink.table.name.toUpperCase() + ((li.superLink.number > 0) ? li.superLink.number + "" : "");
						strJoin += LB + tname + RB + "." + LB + cols[i].columnName.toUpperCase() + RB;
					}
				}
				if (dbmd.databaseType != dbmd.ORACLE) {
					strJoin += dbmd.outerjoinEnd + ")";
				}
			}
			// even if this "super" is not used, a super above it might be
			strJoin = getJoins(strJoin, li.superLink, true, false);
		}

		if (bSub && li.subLinks != null) {
			for (int i = 0; i < li.subLinks.length; i++) {
				if (!li.subLinks[i].bUsed) {
					continue;
				}

				String tname = LB + li.subLinks[i].table.name.toUpperCase() + RB;
				if (li.subLinks[i].number > 0) {
					tname += " " + LB + li.subLinks[i].table.name.toUpperCase() + li.subLinks[i].number + RB;
				}

				if (dbmd.databaseType == dbmd.ORACLE) {
					strJoin += "," + tname;
				} else {
					String s = "LEFT OUTER JOIN"; // always OUTER join subclasses, in case subclass does not exist
					strJoin = "(" + dbmd.outerjoinStart + strJoin + " " + s + " " + tname + " ON ";
				}
				for (int ii = 0, j = 0; ii < cols.length; ii++) {
					if (!cols[ii].primaryKey) {
						continue;
					}

					if (dbmd.databaseType == dbmd.ORACLE && extraWhere.length() > 0) {
						extraWhere += " AND ";
					}
					if (j > 0 && dbmd.databaseType != dbmd.ORACLE) {
						strJoin += " AND ";
					}
					j++;
					tname = table.name.toUpperCase() + ((li.number > 0) ? li.number + "" : "");

					if (dbmd.databaseType == dbmd.ORACLE) {
						extraWhere += LB + tname + RB + "." + LB + cols[i].columnName.toUpperCase() + RB;
						extraWhere += " = ";
						tname = li.superLink.table.name.toUpperCase() + ((li.number > 0) ? li.number + "" : "");
						extraWhere += LB + tname + RB + "." + LB + cols[i].columnName.toUpperCase() + RB + "(+)";
					} else {
						strJoin += LB + tname + RB + "." + LB + cols[ii].columnName.toUpperCase() + RB;
						strJoin += " = ";
						tname = li.subLinks[i].table.name.toUpperCase() + ((li.number > 0) ? li.number + "" : "");
						strJoin += LB + tname + RB + "." + LB + cols[ii].columnName.toUpperCase() + RB;
					}
				}

				if (dbmd.databaseType != dbmd.ORACLE) {
					strJoin += dbmd.outerjoinEnd + ")";
				}
				strJoin = getJoins(strJoin, li.subLinks[i], false, true);
			}
		}
		// links ...
		for (int i = 0; li.links != null && i < li.links.length; i++) {
			if (!li.links[i].bUsed) {
				continue;
			}
			strJoin = createLinkJoins(	li.links[i].bUseLeftJoin, strJoin, table.getLinks()[i], table, table.getLinks()[i].toTable, li.number,
										li.links[i].number);
			strJoin = getJoins(strJoin, li.links[i], true, true);
		}

		return strJoin;

	}

	/**
	 * Generates a JOIN clause covering a sequence of Linkinfo nodes, typically used
	 * for constructing EXISTS-based subqueries.
	 *
	 * @param vec     ordered list of Linkinfo nodes representing a property path
	 * @param liStart starting Linkinfo in the join sequence
	 * @param liEnd   ending Linkinfo in the join sequence
	 * @return SQL fragment containing the required JOIN relationships
	 */
	protected String getJoins(Vector vec, Linkinfo liStart, Linkinfo liEnd) {
		Linkinfo li = liStart;

		if (li.base != null) {
			li = li.base; // use base class in relationship from parent
		}

		String strJoin = LB + li.table.name.toUpperCase() + RB + " " + LB + li.table.name.toUpperCase()
				+ (liStart.number > 0 ? liStart.number + "" : "") + RB;

		int x = vec.size();
		boolean b = false;
		for (int i = 0; i < x; i++) {
			li = (Linkinfo) vec.elementAt(i);
			if (!b && li != liStart) {
				continue;
			}
			b = true;

			Linkinfo lix = (li.base == null) ? li : li.base; // base link is "real" link to parent
			if (!li.bMany) {
				// join with parent link
				strJoin = createLinkJoins(	li.bUseLeftJoin, strJoin, lix.linkFromParent, lix.parent.table, lix.table, lix.parent.number,
											lix.number);
			}

			if (li.base != null) {
				// create join from base to super
				String tname = LB + li.table.name.toUpperCase() + RB;
				if (li.number > 0) {
					tname += " " + LB + li.table.name.toUpperCase() + li.number + RB;
				}

				if (dbmd.databaseType == dbmd.ORACLE) {
					strJoin += ", " + tname;
				} else {
					String s = "INNER JOIN";
					if (bUseLeftJoin || li.bUseLeftJoin) {
						s = "LEFT OUTER JOIN";
					}
					strJoin = "(" + dbmd.outerjoinStart + strJoin + " " + s + " " + tname + " ON ";
				}

				Column[] cols = li.table.getColumns();
				for (int ii = 0, j = 0; ii < cols.length; ii++) {
					if (!cols[ii].primaryKey) {
						continue;
					}
					Column column = cols[ii];

					if (dbmd.databaseType == dbmd.ORACLE && extraWhere.length() > 0) {
						extraWhere += " AND ";
					}
					if (j > 0 && dbmd.databaseType != dbmd.ORACLE) {
						strJoin += " AND ";
					}
					j++;

					tname = li.table.name.toUpperCase() + ((li.number > 0) ? li.number + "" : "");
					if (dbmd.databaseType == dbmd.ORACLE) {
						extraWhere += LB + tname + RB + "." + LB + column.columnName.toUpperCase() + RB;
						extraWhere += " = ";
						tname = li.base.table.name.toUpperCase() + ((li.base.number > 0) ? li.base.number + "" : "");
						extraWhere += LB + tname + RB + "." + LB + column.columnName.toUpperCase() + RB + "(+)";
					} else {
						strJoin += LB + tname + RB + "." + LB + column.columnName.toUpperCase() + RB;
						strJoin += " = ";
						tname = li.base.table.name.toUpperCase() + ((li.base.number > 0) ? li.base.number + "" : "");
						strJoin += LB + tname + RB + "." + LB + column.columnName.toUpperCase() + RB;
					}
				}
				if (dbmd.databaseType != dbmd.ORACLE) {
					strJoin += dbmd.outerjoinEnd + ")";
				}
			}
			if (li == liEnd) {
				break;
			}
		}

		return strJoin;
	}

	/**
	 * Creates JOIN expressions between two tables based on the specified link,
	 * generating equality conditions between corresponding foreign-key columns.
	 *
	 * @param bUseLeftJoin     true to generate LEFT OUTER JOIN instead of INNER JOIN
	 * @param strJoin          current JOIN clause under construction
	 * @param link             link describing the foreign-key relationship
	 * @param fromTable        source table in the relationship
	 * @param toTable          target table in the relationship
	 * @param fromTableNumber  alias number for the source table
	 * @param toTableNumber    alias number for the target table
	 * @return updated JOIN clause
	 */
	private String createLinkJoins(boolean bUseLeftJoin, String strJoin, Link link, Table fromTable, Table toTable, int fromTableNumber,
			int toTableNumber) {
		Column[] fkeys = link.fkeys;
		Column[] toFkeys = fromTable.getLinkToColumns(link, toTable);

		if (fkeys.length != toFkeys.length) {
			throw new RuntimeException("different number of links between tables");
		}

		String tname = LB + toTable.name.toUpperCase() + RB;
		if (toTableNumber > 0) {
			tname += " " + LB + toTable.name.toUpperCase() + toTableNumber + RB;
		}

		if (dbmd.databaseType == dbmd.ORACLE) {
			strJoin += "," + tname;
		} else {
			String s = "INNER JOIN";
			if (this.bUseLeftJoin || bUseLeftJoin) {
				s = "LEFT OUTER JOIN";
			}
			strJoin = "(" + dbmd.outerjoinStart + strJoin + " " + s + " " + tname + " ON ";
		}
		for (int i = 0, j = 0; i < fkeys.length; i++) {
			if (dbmd.databaseType == dbmd.ORACLE && extraWhere.length() > 0) {
				extraWhere += " AND ";
			}
			if (j > 0 && dbmd.databaseType != dbmd.ORACLE) {
				strJoin += " AND ";
			}
			j++;

			tname = fromTable.name.toUpperCase() + ((fromTableNumber > 0) ? fromTableNumber + "" : "");

			if (dbmd.databaseType == dbmd.ORACLE) {
				extraWhere += LB + tname + RB + "." + LB + fkeys[i].columnName.toUpperCase() + RB;
				extraWhere += " = ";
				tname = toTable.name.toUpperCase() + ((toTableNumber > 0) ? toTableNumber + "" : "");
				extraWhere += LB + tname + RB + "." + LB + toFkeys[i].columnName.toUpperCase() + RB + "(+)";
			} else {
				strJoin += LB + tname + RB + "." + LB + fkeys[i].columnName.toUpperCase() + RB;
				strJoin += " = ";
				tname = toTable.name.toUpperCase() + ((toTableNumber > 0) ? toTableNumber + "" : "");
				strJoin += LB + tname + RB + "." + LB + toFkeys[i].columnName.toUpperCase() + RB;
			}
		}

		if (dbmd.databaseType != dbmd.ORACLE) {
			strJoin += dbmd.outerjoinEnd + ")";
		}
		return strJoin;
	}

	/**
	 * Represents a table and its relationship paths within the query translation
	 * process. Each Linkinfo tracks parent/child links, subclass/superclass
	 * relationships, table alias numbering, and join usage flags.
	 */
	class Linkinfo {
		/**
		 * The database table associated with this Linkinfo node, representing the
		 * table reached along a property or inheritance path.
		 */
		Table table;

		/**
		 * The Java class corresponding to this table. Used to load metadata and
		 * navigate inheritance relationships.
		 */
		Class clazz;
		
		/**
		 * Unique alias number assigned to this table reference when producing SQL.
		 */
		int number; // unique number

		/**
		 * Link definition describing how this Linkinfo's table relates to its parent
		 * table.
		 */
		Link linkFromParent;

		/**
		 * Reverse link definition for navigating from the parent table back to this
		 * table.
		 */
		Link linkToParent;
		
		/**
		 * Indicates whether this table is a link table (many-to-many association),
		 * affecting join generation and navigation.
		 */
		boolean bLink; // link table

		/**
		 * Base Linkinfo representing the true superclass table in inheritance
		 * relationships.
		 */
		Linkinfo base; // base link, used for super/sub links
		
		/**
		 * Parent Linkinfo representing the table from which this link originates.
		 */
		Linkinfo parent;

		/**
		 * Linkinfo instance representing the immediate superclass table within the
		 * inheritance hierarchy.
		 */
		Linkinfo superLink;

		/**
		 * Array of Linkinfo instances representing subclasses derived from this table.
		 */
		Linkinfo[] subLinks;
		
		/**
		 * Array of Linkinfo nodes representing relationship links (non-inheritance) from
		 * this table to others.
		 */
		Linkinfo[] links; // same order as Table.Links

		/**
		 * True when the relationship represented by this Linkinfo is a many-side
		 * association, affecting join strategy and DISTINCT usage.
		 */
		boolean bMany;

		/**
		 * Indicates whether this Linkinfo must appear in the generated SQL and therefore
		 * requires join construction.
		 */
		boolean bUsed;
		
		/**
		 * Indicates that LEFT OUTER JOIN should be used when joining this Linkinfo's
		 * table rather than INNER JOIN.
		 */
		boolean bUseLeftJoin;

		/** used internally by this class for setting nodes from root. */
		private Linkinfo(Class c, int num, Linkinfo parent) {
			this.clazz = c;
			number = num;
			this.parent = parent;
			if (c != null) {
				table = database.getTable(c);
			}
		}

		/**
		 * Constructs a root Linkinfo instance for the specified class, initializing table
		 * metadata and populating inheritance and relationship links.
		 *
		 * @param c the class associated with the root table
		 */
		public Linkinfo(Class c) {
			this(c, 0, null);
			counter = 0;
			// load all super and sub classes for root
			loadSuper();
			loadSub();
		}

		/**
		 * Attempts to resolve a property name to a column in this Linkinfo's table
		 * or any superclass table. If found, this Linkinfo is added to the supplied
		 * vector to record the navigation path.
		 *
		 * @param vec          vector receiving the Linkinfo nodes visited
		 * @param propertyName the property or column name to locate
		 * @return matching Column or null if not found
		 */
		public Column findColumn(Vector vec, String propertyName) {
			// first look in columns of this class and its superclass(es)
			Column[] columns = table.getColumns();
			// see is column is a match
			for (int i = 0; i < columns.length; i++) {
				if (columns[i].propertyName.equalsIgnoreCase(propertyName) || propertyName.equalsIgnoreCase(columns[i].columnName)) {
					vec.addElement(this);
					return columns[i];
				}
			}

			// check for property in superclass
			if ((loadSuper() != null)) {
				Column c = superLink.findColumn(vec, propertyName);
				if (c != null) {
					return c;
				}
			}
			return null; // property not found
		}

		/**
		 * Attempts to resolve a property name to a relationship link in this table.
		 * If the link is found, the method returns the target Linkinfo node, creating
		 * it if necessary. The current node is added to the supplied vector.
		 *
		 * @param vec          vector recording the path of Linkinfo nodes navigated
		 * @param propertyName name of the link property to locate
		 * @return Linkinfo representing the target of the link, or null if not found
		 */
		public Linkinfo findLink(Vector vec, String propertyName) {
			loadLinks();
			Link[] tabLinks = table.getLinks();

			// see if link is a match
			for (int i = 0; i < links.length; i++) {
				if (tabLinks[i].propertyName.equalsIgnoreCase(propertyName)) {
					vec.addElement(this);
					if (links[i].bLink) {
						return links[i].findLink(vec, propertyName);
					}
					return links[i];
				}
			}

			// check for property in superclass
			loadSuper();
			if (superLink != null) {
				Linkinfo li = superLink.findLink(vec, propertyName);
				if (li != null) {
					return li;
				}
			}
			return null; // not found
		}

		/**
		 * Loads and initializes Linkinfo instances for all subclasses of this
		 * Linkinfo's class. Inheritance metadata is populated once and reused
		 * for navigation.
		 */
		private void loadSub() {
			if (subLinks == null) {
				Class[] subclasses = table.subclasses;
				this.subLinks = new Linkinfo[subclasses == null ? 0 : subclasses.length];
				for (int i = 0; subclasses != null && i < subclasses.length; i++) {
					subLinks[i] = new Linkinfo(subclasses[i], number, this.parent);
					subLinks[i].linkFromParent = this.linkFromParent;
					subLinks[i].linkToParent = this.linkToParent;
					subLinks[i].bMany = this.bMany;
					subLinks[i].base = (this.base == null) ? this : this.base;
					if (number == 0) {
						// load all for root object
						subLinks[i].loadSub();
					}
				}
			}
		}

		/**
		 * Loads and initializes the Linkinfo instance representing this class’s
		 * immediate superclass, unless it is OAObject or null. Recursively loads
		 * all superclasses for root.
		 *
		 * @return the Linkinfo for the superclass, or null if none exists
		 */
		private Linkinfo loadSuper() {
			if (superLink == null) {
				Class c = clazz.getSuperclass();
				if (c == null || c.equals(OAObject.class)) {
					return null;
				}
				superLink = new Linkinfo(c, number, this.parent);
				superLink.linkFromParent = this.linkFromParent;
				superLink.linkToParent = this.linkToParent;
				superLink.bMany = this.bMany;
				superLink.base = (this.base == null) ? this : this.base;
				if (number == 0) {
					// load all for root object
					superLink.loadSuper();
				}
			}
			return superLink;
		}

		/**
		 * Initializes Linkinfo nodes for each relationship link defined on this
		 * table. Each link is analyzed to determine whether it represents a link
		 * table or a many-side association.
		 */
		private void loadLinks() {
			if (links != null || table == null) {
				return;
			}
			Link[] tabLinks = table.getLinks();
			links = new Linkinfo[tabLinks.length];
			for (int i = 0; i < links.length; i++) {
				counter++;
				Table toTable = tabLinks[i].toTable;
				if (toTable == null) {
					continue;
				}
				links[i] = new Linkinfo(toTable.clazz, counter, this);
				if (toTable.bLink) {
					links[i].table = toTable;
					links[i].bLink = true;
					links[i].bMany = true;
				} else {
					// MANY ?
					final OAGraphInternal og = (OAGraphInternal) OARuntime.graph(table.clazz);
					OAObjectInfo oi = og.objectsInternal().callObjectInfoGetOAObjectInfo(table.clazz);
					OALinkInfo li = og.objectsInternal().callObjectInfoGetLinkInfo(oi, tabLinks[i].propertyName);
					if (li != null && li.getType() == li.MANY) {
						links[i].bMany = true;
					}
				}
				links[i].linkFromParent = tabLinks[i];
				links[i].linkToParent = tabLinks[i].getReverseLink();
			}
		}
	}

}
