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

import java.sql.ResultSet;
import java.sql.SQLException;

import com.viaoa.object.OAObject;

/**
 * Defines methods for populating {@link com.viaoa.object.OAObject} instances
 * from JDBC result sets. Implementations map {@link java.sql.ResultSet}
 * columns to OAObject properties using {@link Table} and {@link Column}
 * metadata.
 */
public interface DataAccessObject {

	/**
	 * Holds state for processing a {@link java.sql.ResultSet}, including
	 * caching flags and a reference to the current result set used when
	 * constructing OAObject instances.
	 */
    public class ResultSetInfo {
    	/**
    	 * The current JDBC {@link ResultSet} being processed. Updated through
    	 * {@link #reset(ResultSet)}.
    	 */
        ResultSet rs;
        
        /**
         * Flag indicating whether the requested object was found in the cache
         * rather than created from the result set.
         */
        boolean foundInCache;
        
        /**
         * Resets this {@code ResultSetInfo} instance with a new {@link ResultSet}
         * and clears the cache-hit flag.
         *
         * @param rs the result set to associate with this info object
         */
        public void reset(ResultSet rs) {
            this.rs = rs;
            foundInCache = false;
        }

        /**
         * Returns whether the object corresponding to this result set row was
         * found in the cache rather than populated from JDBC data.
         *
         * @return true if the object was retrieved from cache
         */
        public boolean getFoundInCache() {
            return this.foundInCache;
        }
        
        /**
         * Sets whether the object for this result set row was retrieved from
         * cache rather than created from JDBC values.
         *
         * @param b true if the object came from cache
         */
        public void setFoundInCache(boolean b) {
            this.foundInCache = b;
        }
        
        /**
         * Returns the associated JDBC {@link ResultSet} for this info object.
         *
         * @return the current ResultSet
         */
        public ResultSet getResultSet() {
            return rs;
        }
    }
    
    /**
     * Creates or retrieves an {@link OAObject} populated using the data at the
     * current row of the given {@link ResultSetInfo}. Implementations translate
     * column values into object properties.
     *
     * @param rsi the result set wrapper providing JDBC data and cache state
     * @return the populated OAObject
     * @throws SQLException if a JDBC error occurs
     */
    public OAObject getObject(ResultSetInfo rsi) throws SQLException;
    
    /**
     * Returns a comma-separated list of column names required to uniquely
     * identify an object (its primary key columns) during SELECT operations.
     *
     * @return SQL column list used for primary-key selection
     */
    public String getPkeySelectColumns();

    /**
     * Returns a comma-separated list of all database columns required to fully
     * populate an OAObject instance during SELECT operations.
     *
     * @return SQL column list used for full object selection
     */
    public String getSelectColumns();
}
