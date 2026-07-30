package com.viaoa.datasource.jdbc;

import java.lang.reflect.Method;

import com.viaoa.annotation.*;
import com.viaoa.datasource.jdbc.db.*;
import com.viaoa.lang.OAArray;
import com.viaoa.lang.OAString;
import com.viaoa.metadata.OAObjectInfo;
import com.viaoa.metadata.OAPropertyInfo;
import com.viaoa.object.OAObject;
import com.viaoa.runtime.OARuntime;
import com.viaoa.text.OATextCode;

public class OADatabaseService {

    public OADatabaseService() {
    }
	
	/**
	 * Updates the database metadata using annotations declared on the supplied
	 * classes. Column definitions are created first, followed by table-level
	 * updates such as foreign keys, link tables, and indexes.
	 *
	 * @param database the database metadata container to update
	 * @param classes  the classes whose annotations define table and column structure
	 * @throws Exception if required annotations are missing or inconsistent
	 */
	public void update(Database database, Class<? extends OAObject>[] classes) throws Exception {
		if (classes == null) {
			return;
		}
		for (Class<? extends OAObject> c : classes) {
			_createColumns(database, c);
		}
		for (Class<? extends OAObject> c : classes) {
			_updateTable(database, c);
		}
	}

	
	/**
	 * Creates or updates column definitions for the table associated with the
	 * specified class, based on its {@link OAProperty} and {@link OAColumn}
	 * annotations.
	 * <p>
	 * Only primary-key and regular property columns are created in this phase.
	 * Foreign-key and link-column processing occurs later during table update.
	 *
	 * @param database the database metadata container
	 * @param clazz    the class whose annotated properties define table columns
	 * @throws Exception if required table annotations are missing
	 */
	private void _createColumns(Database database, Class<? extends OAObject> clazz) throws Exception {
		Method[] methods = clazz.getDeclaredMethods(); // need to get all access types, since some could be private.  does not get superclass methods

		OATable dbTable = (OATable) clazz.getAnnotation(OATable.class);
		if (dbTable == null) {
			throw new Exception("Annotation for Table not defined for this class");
		}

		Table table = database.getTable(clazz);
		if (table == null) {
			String s = dbTable.name();
			if (s.length() == 0) {
				s = clazz.getSimpleName();
			}
			table = new Table(s, clazz);
			database.addTable(table);
		}

		// 1: create pkey and regular columns
		for (Method m : methods) {
			OAProperty oaprop = (OAProperty) m.getAnnotation(OAProperty.class);
			if (oaprop == null) {
				continue;
			}

			if (oaprop.isFkeyOnly()) continue;
			
			OAColumn dbcol = (OAColumn) m.getAnnotation(OAColumn.class);
			if (dbcol == null) {
				continue;
			}

			OAId oaid = (OAId) m.getAnnotation(OAId.class);

			String name = OATextCode.getPropertyName(m.getName());

			String colName = dbcol.name(); // will be "", if the property name should be used.
			if (colName == null || colName.length() == 0) {
				colName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
			}

			Column column = new Column(colName, name, dbcol.sqlType(), dbcol.maxLength());
			String s = dbcol.lowerName();
			if (OAString.isNotEmpty(s)) {
				column.columnLowerName = s;
			}
			if (oaprop != null) {
				column.decimalPlaces = oaprop.decimalPlaces();
				column.json = oaprop.isJson();
			}
			if (oaid != null) {
				column.primaryKey = true;
				column.guid = oaid.guid();
				column.assignNextNumber = oaid.autoAssign();
			}
			if (oaprop != null) {
				column.unicode = oaprop.isUnicode();
			}
			column.fullTextIndex = dbcol.isFullTextIndex();

			table.addColumn(column);
		}
	}

	/**
	 * Updates an existing database table definition using annotations declared on
	 * the specified class.
	 * <p>
	 * This includes creating foreign-key columns, link-table mappings, link
	 * relationships, and table-level indexes based on {@link OAOne}, {@link OAMany},
	 * {@link OALinkTable}, {@link OAColumn}, and {@link OAIndex} annotations.
	 *
	 * @param database the database metadata container
	 * @param clazz    the class whose annotations define table-level metadata
	 * @throws Exception if required table or column annotations are missing or inconsistent
	 */
	private void _updateTable(final Database database, final Class<? extends OAObject> clazz) throws Exception {

		Method[] methods = clazz.getDeclaredMethods(); // need to get all access types, since some could be private. does not get superclass methods

		OATable dbTable = (OATable) clazz.getAnnotation(OATable.class);
		if (dbTable == null) {
			throw new Exception("Annotation for Table not defined for this class");
		}

		final Table table = database.getTable(clazz);
		if (table == null) {
			throw new Exception("Table for class=" + clazz + " was not found");
		}
		// 2: create fkey columns and links
		for (Method m : methods) {
			OAProperty oaprop = (OAProperty) m.getAnnotation(OAProperty.class);
			OAColumn dbcol = (OAColumn) m.getAnnotation(OAColumn.class);
			OAOne oaone = (OAOne) m.getAnnotation(OAOne.class);
			OAMany oamany = (OAMany) m.getAnnotation(OAMany.class);
			OALinkTable oalt = (OALinkTable) m.getAnnotation(OALinkTable.class);

			String[] fkcols = new String[0];
			if (oaone != null && oalt == null) {
				OAObjectInfo oi = callInfoGetObjectInfo(clazz);
				for (OAFkey fk : oaone.fkeys()) {
					OAPropertyInfo pi = oi.getPropertyInfo(fk.fromProperty());
					if (pi == null) {
						throw new Exception("Class " + clazz.getSimpleName() + " is missing get/set method for property "
								+ fk.fromProperty() + ", that should have been added with OAOne link " + m.getName());
					}
					fkcols = OAArray.add(fkcols, pi.getOAColumn().name());
				}
			}

			if (fkcols.length > 0) {
				// if (dbfk != null) {
				if (dbcol != null) {
					throw new Exception("fkey column should not have a column annotation defined, method is " + m.getName());
				}
				if (oaone == null) {
					throw new Exception("method with fkey does not have a One annotation defined, method is " + m.getName());
				}
				if (oamany != null) {
					throw new Exception("method with fkey should not have a Many annotation defined, method is " + m.getName());
				}

				Class<?> returnClass = m.getReturnType();
				if (returnClass == null) {
					throw new Exception("method with fkey does not have a return class type, method is " + m.getName());
				}

				OAClass oacx = (OAClass) returnClass.getAnnotation(OAClass.class);
				if (oacx == null || !oacx.useDataSource()) {
					continue;
				}

				OATable toTable = (OATable) returnClass.getAnnotation(OATable.class);
				if (toTable == null) {
					throw new Exception("class for fkey does not have a Table annotation defined, method is " + m.getName());
				}

				Table fkTable = database.getTable(returnClass);
				if (fkTable == null) {
					fkTable = new Table(toTable.name(), returnClass);
					database.addTable(fkTable);
				}

				// tables[COLORCODE].addLink("orders", tables[ORDER], "colorCode", new int[] {0});
				//   tables[WORKER].addLink("orderProductionAreas", tables[ORDERPRODUCTIONAREA], "worker", new int[] {0});

				//was: String[] fkcols = dbfk.columns();

				int[] poss = new int[0];
				for (String sfk : fkcols) {
					Column col = table.getColumn(sfk, null);
					if (col != null) {
						poss = OAArray.add(poss, table.getColumnPosition(col));
					} else {
						poss = OAArray.add(poss, table.getColumns().length);
						Column c = new Column(sfk, true);
						table.addColumn(c);
					}
				}
				table.addLink(OATextCode.getPropertyName(m.getName()), fkTable, oaone.reverseName(), poss);
			} else if (oalt != null) {
				Table linkTable = database.getTable(oalt.name());
				if (linkTable == null) {
					linkTable = new Table(oalt.name(), true);
					database.addTable(linkTable);
				}
				// create columns for link table
				// create link for table to linkTable
				// create link for linktable to table
				int[] poss = new int[0]; // pos for pk columns in table
				int[] poss2 = new int[0]; // pos for fkey columsn in linkTable
				String[] indexColumns = new String[0];
				Column[] cols = table.getColumns();
				int j = 0;
				for (int i = 0; i < cols.length; i++) {
					if (!cols[i].primaryKey) {
						continue;
					}

					poss = OAArray.add(poss, i);
					poss2 = OAArray.add(poss2, linkTable.getColumns().length);

					if (j >= oalt.columns().length) {
						throw new Exception(
								"mismatch between linktable fkey columns and pkey columns, more pkeys. method is " + m.getName());
					}

					Column c = new Column(oalt.columns()[j], "", cols[i].getSqlType(), cols[i].maxLength);
					c.primaryKey = false; // no pkeys in linkTable, only indexes
					linkTable.addColumn(c);

					indexColumns = (String[]) OAArray.add(String.class, indexColumns, oalt.columns()[j]);
					j++;
				}
				if (j < oalt.columns().length) {
					throw new Exception("mismatch between fkey columns and pkey columns, more fkeys. method is " + m.getName());
				}

				if (oamany != null) {
					table.addLink(OATextCode.getPropertyName(m.getName()), linkTable, oamany.reverseName(), poss);
				} else {
					table.addLink(OATextCode.getPropertyName(m.getName()), linkTable, oaone.reverseName(), poss);
				}

				if (oamany != null) {
					linkTable.addLink(oamany.reverseName(), table, OATextCode.getPropertyName(m.getName()), poss2);
				} else {
					linkTable.addLink(oaone.reverseName(), table, OATextCode.getPropertyName(m.getName()), poss2);
				}

				String s = oalt.indexName();
				if (s != null) {
					linkTable.addIndex(new Index(s, indexColumns));
				}
			} else if (oaone != null) {
				if (oamany != null) {
					throw new Exception(
							"method with OAOne annotation should not have a OAMany annotation defined, method is " + m.getName());
				}

				// link using pkey columns
				int[] poss = new int[0];
				Column[] cols = table.getColumns();
				for (int i = 0; i < cols.length; i++) {
					if (cols[i].primaryKey) {
						poss = OAArray.add(poss, i);
					}
				}
				Table tt = database.getTable(m.getReturnType());
				if (tt != null) {
					table.addLink(OATextCode.getPropertyName(m.getName()), tt, oaone.reverseName(), poss);
				}
			} else if (oamany != null) {
				Column[] cols = table.getColumns();
				int[] poss = new int[0]; // pos for pk columns in table
				for (int i = 0; i < cols.length; i++) {
					if (!cols[i].primaryKey) {
						continue;
					}
					poss = OAArray.add(poss, i);
				}
				Class<? extends OAObject> c = callAnnotationGetHubObjectClass(oamany, m);
				Table tt = database.getTable(c);
				if (tt != null) {
					table.addLink(OATextCode.getPropertyName(m.getName()), tt, oamany.reverseName(), poss);
				}
			}
		}

		// Indexes
		OAIndex[] indexes = dbTable.indexes();
		for (OAIndex ind : indexes) {
			String[] ss = new String[0];
			OAIndexColumn[] dbics = ind.columns();
			for (OAIndexColumn dbic : dbics) {
				ss = (String[]) OAArray.add(String.class, ss, dbic.name());
			}
			table.addIndex(new Index(ind.name(), ss, ind.fkey()));
		}
	}
	
	public OAObjectInfo callInfoGetObjectInfo(Class<? extends OAObject> clazz) {
		return OARuntime.oa(clazz).info(clazz);
	}
	public Class<? extends OAObject> callAnnotationGetHubObjectClass(OAMany annotation, Method method) {
		return OARuntime.oa().internal().objects().annotation().getHubObjectClass(annotation, method);
	}
}


