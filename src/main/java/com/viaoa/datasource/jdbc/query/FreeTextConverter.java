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

import java.util.StringTokenizer;

import com.viaoa.lang.OAString;

/**
 * Utility for converting free-text search tokens into database-specific SQL fragments.
 * <p>
 * Supports common full-text syntaxes across multiple database engines (SQL Server, Oracle, MySQL, PostgreSQL),
 * translating OA "contains" or "like" operators into the correct dialect (e.g. CONTAINS, MATCH AGAINST, etc.).
 * <p>
 * Used internally by {@link QueryConverter} during query translation when a {@code Column.fullTextIndex} is defined.
 */
public class FreeTextConverter {

    /**
     * Convert a phrase in a query for freeform text search.
     * Currently supports SQL Server
     */
    public static String convertForFreeText(String phrase) {
        if (phrase == null) return "";
        String s;

        // parse keyword:   a '**' needs to be converted to FORMSOF(...)
        String newPhrase = "";

        String sep = "(), \"&";
        StringTokenizer st = new StringTokenizer(phrase, sep, true);
        String hold = "";
        boolean bInQuote = false;

        while (st.hasMoreTokens()) {
            s = st.nextToken();

            char ch = s.charAt(0);

            if (bInQuote) {
                if (ch != '\"') {
                    hold += s;
                    continue;
                }
                bInQuote = false;
            }
            else if (ch == '\"') {
                bInQuote = true;
                if (hold.trim().length() == 0) {
                    hold = "";
                    continue;
                }
                s = "AND";
                ch = 'A';
            }
            else if (ch == '[') {
                s = "(";
                ch = '(';
            }
            else if (ch == ']') {
                s = ")";
                ch = ')';
            }
            else if (ch == ',') {
                s = "and";
            }
            else if (ch == '(' || ch == ')') {
            }
            else if (ch == '&') {
                if (hold.length() > 0 && !hold.endsWith(" ")) {
                    hold += "&";
                    continue;
                }
                if (!newPhrase.endsWith(" ")) {
                    newPhrase += "&";
                    continue;
                }
                s = "and";
                ch = 'a';
            }
            else if (s.equalsIgnoreCase("and") || s.equalsIgnoreCase("or") || s.equalsIgnoreCase("not") || s.equalsIgnoreCase("near")
                    || s.equalsIgnoreCase("like")) {
            }
            else {
                hold += s;
                continue;
            }

            if (hold.length() > 0) {
                if (ch != '\"') hold = hold.trim();
                if (hold.length() > 0) {
                    hold = OAString.convert(hold, "'", "''");
                    if (newPhrase.length() > 0 && !newPhrase.endsWith(" ")) newPhrase += " ";
                    newPhrase += "\"" + hold + "\"";
                }
                hold = "";
            }

            if (ch != '\"' && s != null && s.length() > 0) {
                if (!newPhrase.endsWith(" ")) newPhrase += " ";
                newPhrase += s;
            }
            bInQuote = false;
        }

        if (hold.length() > 0) {
            hold = hold.trim();
            hold = OAString.convert(hold, "'", "''");
            if (newPhrase.length() > 0 && !newPhrase.endsWith(" ")) newPhrase += " ";
            newPhrase += "\"" + hold + "\"";
        }
        return newPhrase;
    }
}
