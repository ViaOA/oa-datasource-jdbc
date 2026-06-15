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

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.StringTokenizer;

import com.viaoa.converter.OAConverter;
import com.viaoa.datasource.jdbc.db.Column;
import com.viaoa.datasource.jdbc.db.DBMetaData;
import com.viaoa.datetime.OADate;
import com.viaoa.datetime.OADateTime;
import com.viaoa.datetime.OATime;
import com.viaoa.lang.OAString;
import com.viaoa.object.OAObject;
import com.viaoa.object.OAObjectKey;
import com.viaoa.reflect.OAReflect;

/**
 * Converts OA property values into SQL-ready literal text (or parameter values)
 * honoring database vendor rules and column metadata.
 * <p>
 * Supports {@link com.viaoa.object.OAObjectKey} and lists (for {@code IN} clauses),
 * boolean mappings via {@code DBMetaData.objectTrue/objectFalse}, numeric scale,
 * date/time classes ({@code OADate, OATime, OADateTime}), and optional JSON casting
 * (e.g., Postgres {@code ::jsonb}) for JSON columns.
 * </p>
 *
 * @see com.viaoa.datasource.jdbc.db.DBMetaData
 * @see com.viaoa.datasource.jdbc.db.Column
 */
public class ConverterDelegate {

	/**
	 * Converts a value into a SQL-compatible literal string based on the
	 * supplied database metadata and column definition.
	 *
	 * @param dbmd database metadata defining vendor-specific behavior
	 * @param column column metadata describing SQL type and constraints
	 * @param value the value to convert
	 * @return SQL-ready literal representation of the value
	 */
	public static String convert(DBMetaData dbmd, Column column, Object value) {
		Class clazz = null;

		if (column == null) {
			if (value == null) {
				return "NULL";
			}

			if (value instanceof List) {
				// 20220101 list of oaObjectKey used by IN
				String str = "";
				for (Object objx : ((List) value)) {
					if (objx instanceof OAObjectKey) {
						Object[] ids = ((OAObjectKey) objx).getObjectIds();
						String s = "";
						for (Object objz : ids) {
							if (s.length() > 0) {
								s += ", ";
							}
							if (objz == null) {
								s += "null";
							} else {
								s += convertToString(dbmd, objz, true, 0, 0, null);
							}
						}

						if (ids != null && ids.length > 1) {
							s = "(" + s + ")";
						}
						if (str.length() > 0) {
							str += ", ";
						}
						str += s;
					} else {
						if (str.length() > 0) {
							str += ", ";
						}
						String s = convertToString(dbmd, objx, true, 0, 0, null);
						str += s;

					}
				}
				return str;
			}

			return value.toString();
		}

		if (value instanceof OAObject) {
			value = ((OAObject) value).getObjectKey();
		}
		if (value instanceof OAObjectKey) {
			String str = "";
			Object[] ids = ((OAObjectKey) value).getObjectIds();
			String s = "";
			for (Object objz : ids) {
				if (s.length() > 0) {
					s += ", ";
				}
				if (objz == null) {
					s += "null";
				} else {
					s += convertToString(dbmd, objz, true, 0, 0, null);
				}
			}

			if (ids != null && ids.length > 1) {
				s = "(" + s + ")";
			}
			if (str.length() > 0) {
				str += ", ";
			}
			str += s;
			return str;
		}

		switch (column.type) {
		case Types.CLOB:
		case Types.LONGVARCHAR:
		case Types.VARCHAR:
			clazz = String.class;
			if (dbmd.blanksAsNulls && ((value instanceof String) && ((String) value).length() == 0)) {
				value = null;
			}
			break;

		case Types.BIGINT:
		case Types.DECIMAL:
		case Types.FLOAT:
		case Types.INTEGER:
		case Types.NUMERIC:
		case Types.REAL:
		case Types.SMALLINT:
		case Types.DOUBLE:
		case Types.TINYINT:
			clazz = Number.class;
			break;

		case Types.CHAR:
			clazz = Character.class;
			break;

		case Types.BIT:
		case Types.BOOLEAN:
			clazz = Boolean.class;
			break;

		case Types.DATE:
			clazz = OADate.class;
			break;
		case Types.TIME:
			clazz = OATime.class;
			break;
		case Types.TIMESTAMP:
			clazz = OADateTime.class;
			// 20170206 removed for sqlserver, that now has a DateTime
			// if (dbmd.databaseType == dbmd.SQLSERVER) clazz = Number.class;
			break;

		case Types.LONGVARBINARY:
		case Types.VARBINARY:
			// todo: qqq these are for byte[]
			// throw new RuntimeException("SQL Type not mapped to a class");
		default:
			// throw new RuntimeException("SQL Type not known");
		}

		if (clazz != null && value != null) {
			value = OAConverter.convert(clazz, value);
		}

		return convertToString(dbmd, value, true, column.maxLength, column.decimalPlaces, column);
	}

	/**
	 * Determines whether values for the specified column type require
	 * single-quote wrapping in SQL.
	 *
	 * @param column the column metadata
	 * @return {@code true} if single quotes are required
	 */
	public static boolean areSingleQuotesNeeded(Column column) {
		switch (column.type) {
		case Types.CLOB:
		case Types.LONGVARCHAR:
		case Types.VARCHAR:
			return true;
		}
		return false;
	}

	/**
	 * Converts a Java value into a SQL literal string using database-specific
	 * formatting rules.
	 *
	 * @param dbmd database metadata defining vendor-specific behavior
	 * @param obj the value to convert
	 * @param bConvertSingleQuotes {@code true} to escape and wrap string values
	 * @param maxLength maximum allowed string length
	 * @param decimalPlaces numeric decimal precision
	 * @param column column metadata, or {@code null}
	 * @return SQL-ready literal string
	 */
	protected static String convertToString(DBMetaData dbmd, Object obj, boolean bConvertSingleQuotes, int maxLength, int decimalPlaces,
			Column column) {
		if (obj == null) {
			return "NULL";
		}
		Class c = obj.getClass();

		if (c.equals(Boolean.class)) {
			boolean b = ((Boolean) obj).booleanValue();
			if (dbmd.objectTrue != null) {
				obj = b ? dbmd.objectTrue : dbmd.objectFalse;
			} else {
				return (b ? "1" : "0");
			}
			return obj.toString();
		}

		if (Number.class.isAssignableFrom(c)) {
			String fmt = null;
			if (decimalPlaces > 0) {
				if (obj instanceof BigDecimal) {
					if (((BigDecimal) obj).scale() <= decimalPlaces) {
						return ((BigDecimal) obj).toPlainString();
					}
				}
				if (OAReflect.isFloat(c)) {
					fmt = ".0";
					for (int i = 1; i < decimalPlaces; i++) {
						fmt += "0";
					}
				}
			}
			return OAConverter.toString(obj, fmt);
		}

		if (c.equals(OADate.class)) {
			String s = ((OADate) obj).toString("yyyy-MM-dd");
			if (dbmd.databaseType == dbmd.ACCESS) {
				return "#" + s + "#";
			}
			return "{d '" + s + "'}";
		}

		if (c.equals(OATime.class)) {
			OATime time = (OATime) obj;
			String s = OAString.fmt("" + time.getHour(), "2R00") + ":" + OAString.fmt("" + time.getMinute(), "2R00") + ":"
					+ OAString.fmt("" + time.getSecond(), "2R00");
			if (dbmd.databaseType == dbmd.ACCESS) {
				return "#" + s + "#";
			}
			return "{t '" + s + "'}";
		}

		if (c.equals(OADateTime.class)) {
			String s = ((OADateTime) obj).toString("yyyy-MM-dd HH:mm:ss");
			if (dbmd.databaseType == dbmd.ACCESS) {
				return "#" + s + "#";
			}
			return "{ts '" + s + "'}";
		}

		String s = OAConverter.toString(obj);

		if (maxLength > 0 && s.length() > maxLength) {
			s = s.substring(0, maxLength);
		}

		if (bConvertSingleQuotes) {
			s = convertSingleQuotes(dbmd, s);
			s = "'" + s + "'";
			if (column != null && column.unicode) {
				s = "N" + s;
			}
		}
		return s;
	}

	/**
	 * Escapes single-quote characters in a string according to database-specific
	 * escape rules.
	 *
	 * @param dbmd database metadata defining escape behavior
	 * @param value the string value to escape
	 * @return escaped string value
	 */
	protected static String convertSingleQuotes(DBMetaData dbmd, String value) {
		if (value == null) {
			return null;
		}
		// convert all ' to ''
		if (value.indexOf('\'') >= 0 || value.indexOf('\\') >= 0) {
			StringTokenizer st = new StringTokenizer(value, "'\\", true);
			StringBuffer newValue = new StringBuffer(value.length() + 16);
			while (st.hasMoreTokens()) {
				String s = st.nextToken();
				if (s.charAt(0) == '\'') {
					if (dbmd.useBackslashForEscape) {
						newValue.append("\\'");
					} else {
						newValue.append("''");
					}
				} else if (s.charAt(0) == '\\' && dbmd.useBackslashForEscape) {
					newValue.append("\\");
				} else {
					newValue.append(s);
				}
			}
			value = new String(newValue);
		}
		return value;
	}
}
