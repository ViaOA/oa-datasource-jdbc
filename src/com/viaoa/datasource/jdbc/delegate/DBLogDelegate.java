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

import java.util.logging.Logger;

/**
 * Structured database activity logging helper.
 * <p>
 * Emits normalized {@code INSERT}, {@code UPDATE}, {@code DELETE}, and {@code DDL}
 * lines with bounded markers {@code [[BEGIN[... ]END]]} and per-parameter segments,
 * suitable for later parsing and recovery.
 * </p>
 */
public class DBLogDelegate {
	private static Logger LOG = Logger.getLogger(DBLogDelegate.class.getName());

	/**
	 * Logs a DELETE SQL statement using a structured marker format.
	 *
	 * @param sql the DELETE SQL statement
	 */
	public static void logDelete(String sql) {
		LOG.fine("DELETE: [[BEGIN[" + sql + "]END]]");
	}

	/**
	 * Logs a DDL SQL statement using a structured marker format.
	 *
	 * @param sql the DDL SQL statement
	 */
	public static void logDDL(String sql) {
		LOG.fine("DDL: [[BEGIN[" + sql + "]END]]");
	}

	/**
	 * Logs an INSERT SQL statement along with its parameter values.
	 * <p>
	 * Each parameter value is logged using a bounded marker to preserve ordering
	 * and enable later extraction.
	 *
	 * @param sql the INSERT SQL statement
	 * @param params the parameter values associated with the statement
	 */
	public static void logInsert(String sql, Object[] params) {
		String s = "";
		for (int i = 0; params != null && i < params.length; i++) {
			s += "[[PARAM" + i + "[" + params[i] + "]END]]";
		}
		LOG.fine("INSERT: [[BEGIN[" + sql + s + "]END]]");
	}

	/**
	 * Logs an UPDATE SQL statement along with its parameter values.
	 * <p>
	 * Each parameter value is logged using a bounded marker to preserve ordering
	 * and enable later extraction.
	 *
	 * @param sql the UPDATE SQL statement
	 * @param params the parameter values associated with the statement
	 */
	public static void logUpdate(String sql, Object[] params) {
		String s = "";
		for (int i = 0; params != null && i < params.length; i++) {
			s += "[[PARAM" + i + "[" + params[i] + "]END]]";
		}
		LOG.fine("UPDATE: [[BEGIN[" + sql + s + "]END]]");
	}

}
