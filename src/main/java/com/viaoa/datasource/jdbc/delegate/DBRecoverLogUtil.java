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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import com.viaoa.lang.OAArray;

/**
 * Parses lines written by {@link DBLogDelegate} to reconstruct commands
 * and parameters for recovery or audit replays.
 * <p>
 * Expects {@code FINE: } log lines with {@code [[BEGIN[... ]END]]} markers;
 * accumulates {@code PARAM} segments and recognizes INSERT/UPDATE/DELETE/DDL events.
 * </p>
 */
public class DBRecoverLogUtil {

	/**
	 * Reads structured database log entries from the supplied input stream and
	 * parses SQL commands and parameter values.
	 * <p>
	 * This method expects log lines prefixed with {@code FINE: } and bounded by
	 * {@code [[BEGIN[... ]END]]} markers. INSERT, UPDATE, DELETE, and DDL commands
	 * are recognized, and parameter segments are accumulated as encountered.
	 *
	 * @param is the input stream containing database log output
	 * @throws IOException if an I/O error occurs while reading the stream
	 */
	public void recover(InputStream is) throws IOException {

		BufferedReader br = new BufferedReader(new InputStreamReader(is));

		byte[] bs = new byte[8096];
		String[] params = null;
		String command = null;
		boolean bParam = false;

		for (int i = 0;; i++) {
			String line = br.readLine();
			if (line == null) {
				break;
			}

			if (!line.startsWith("FINE: ")) {
				continue;
			}
			line = line.substring(5);

			if (line.startsWith("PARAM: [[BEGIN[")) {
				line = line.substring(15);
				bParam = true;
			} else if (line.startsWith("INSERT: [[BEGIN[")) {
				line = line.substring(16);
			} else if (line.startsWith("UPDATE: [[BEGIN[")) {
				line = line.substring(16);

			} else if (line.startsWith("DELETE: [[BEGIN[")) {
				line = line.substring(16);
			} else if (line.startsWith("DDL: [[BEGIN[")) {
				line = line.substring(13);
			}

			if (line.endsWith("]END]]")) {
				line = line.substring(0, line.length() - 6);

				if (bParam) {
					params = (String[]) OAArray.add(String.class, params, line);
					bParam = false;
				} else {
					// execute command
				}
			}

		}

		/*

		Apr 5, 2010 2:37:35 PM com.viaoa.datasource.jdbc.delegate.DBLogDelegate logInsert
		FINE: INSERT: [[BEGIN[INSERT INTO PRODUCTIONDATE (WORKING, ID, TYPE, DATEVALUE) VALUES (NULL, 491, 2, {d '2010-03-28'})]END]]


		*/

	}

}
