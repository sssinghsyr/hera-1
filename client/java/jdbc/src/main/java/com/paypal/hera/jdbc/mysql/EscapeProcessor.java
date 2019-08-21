/*
 * Copyright (c) 2002, 2018, Oracle and/or its affiliates. All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License, version 2.0, as published by the
 * Free Software Foundation.
 *
 * This program is also distributed with certain software (including but not
 * limited to OpenSSL) that is licensed under separate terms, as designated in a
 * particular file or component or in included license documentation. The
 * authors of MySQL hereby grant you an additional permission to link the
 * program and your derivative works with the separately licensed software that
 * they have included with MySQL.
 *
 * Without limiting anything contained in the foregoing, this file, which is
 * part of MySQL Connector/J, is also subject to the Universal FOSS Exception,
 * version 1.0, a copy of which can be found at
 * http://oss.oracle.com/licenses/universal-foss-exception.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License, version 2.0,
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301  USA
 */

package com.paypal.hera.jdbc.mysql;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TimeZone;


import com.paypal.hera.jdbc.mysql.EscapeTokenizer;
import com.paypal.hera.ex.HeraSQLException;
import com.paypal.hera.jdbc.mysql.StringUtils;

/**
 * EscapeProcessor performs all escape code processing as outlined in the JDBC spec by JavaSoft.
 */
public class EscapeProcessor {
    private static Map<String, String> JDBC_CONVERT_TO_MYSQL_TYPE_MAP;
    
    public final static byte USES_VARIABLES_FALSE = 0;
    public final static byte USES_VARIABLES_TRUE = 1;

    static {
        Map<String, String> tempMap = new HashMap<>();

        tempMap.put("BIGINT", "0 + ?");
        tempMap.put("BINARY", "BINARY");
        tempMap.put("BIT", "0 + ?");
        tempMap.put("CHAR", "CHAR");
        tempMap.put("DATE", "DATE");
        tempMap.put("DECIMAL", "0.0 + ?");
        tempMap.put("DOUBLE", "0.0 + ?");
        tempMap.put("FLOAT", "0.0 + ?");
        tempMap.put("INTEGER", "0 + ?");
        tempMap.put("LONGVARBINARY", "BINARY");
        tempMap.put("LONGVARCHAR", "CONCAT(?)");
        tempMap.put("REAL", "0.0 + ?");
        tempMap.put("SMALLINT", "CONCAT(?)");
        tempMap.put("TIME", "TIME");
        tempMap.put("TIMESTAMP", "DATETIME");
        tempMap.put("TINYINT", "CONCAT(?)");
        tempMap.put("VARBINARY", "BINARY");
        tempMap.put("VARCHAR", "CONCAT(?)");

        JDBC_CONVERT_TO_MYSQL_TYPE_MAP = Collections.unmodifiableMap(tempMap);

    }

    /**
     * Escape process one string
     *
     * @param sql
     *            the SQL to escape process.
     * @param defaultTimeZone
     *            time zone
     * @param serverSupportsFractionalSecond
     *            flag indicating if server supports fractional seconds
     * @param serverTruncatesFractionalSecond
     *            flag indicating if server truncates fractional seconds (sql_mode contains TIME_TRUNCATE_FRACTIONAL)
     * @param exceptionInterceptor
     *            exception interceptor
     *
     * @return the SQL after it has been escape processed.
     *
     * @throws SQLException
     *             if error occurs
     */
    public static final Object escapeSQL(String sql) throws java.sql.SQLException {
        boolean replaceEscapeSequence = false;
        String escapeSequence = null;

        if (sql == null) {
            return null;
        }

        /*
         * Short circuit this code if we don't have a matching pair of "{}". - Suggested by Ryan Gustafason
         */
        int beginBrace = sql.indexOf('{');
        int nextEndBrace = (beginBrace == -1) ? (-1) : sql.indexOf('}', beginBrace);

        if (nextEndBrace == -1) {
            return sql;
        }

        StringBuilder newSql = new StringBuilder();

        EscapeTokenizer escapeTokenizer = new EscapeTokenizer(sql);

        byte usesVariables = USES_VARIABLES_FALSE;
        boolean callingStoredFunction = false;

        while (escapeTokenizer.hasMoreTokens()) {
            String token = escapeTokenizer.nextToken();

            if (token.length() != 0) {
                if (token.charAt(0) == '{') { // It's an escape code

                    if (!token.endsWith("}")) {
                    	throw new HeraSQLException("Not supported");
                    }

                    if (token.length() > 2) {
                        int nestedBrace = token.indexOf('{', 2);

                        if (nestedBrace != -1) {
                            StringBuilder buf = new StringBuilder(token.substring(0, 1));

                            Object remainingResults = escapeSQL(token.substring(1, token.length() - 1));

                            String remaining = null;

                            if (remainingResults instanceof String) {
                                remaining = (String) remainingResults;
                            } else {
                                remaining = ((EscapeProcessorResult) remainingResults).escapedSql;

                                if (usesVariables != USES_VARIABLES_TRUE) {
                                    usesVariables = ((EscapeProcessorResult) remainingResults).usesVariables;
                                }
                            }

                            buf.append(remaining);

                            buf.append('}');

                            token = buf.toString();
                        }
                    }

                    // nested escape code
                    // Compare to tokens with _no_ whitespace
                    String collapsedToken = removeWhitespace(token);

                    /*
                     * Process the escape code
                     */
                    if (StringUtils.startsWithIgnoreCase(collapsedToken, "{escape")) {
                        try {
                            StringTokenizer st = new StringTokenizer(token, " '");
                            st.nextToken(); // eat the "escape" token
                            escapeSequence = st.nextToken();

                            if (escapeSequence.length() < 3) {
                                newSql.append(token); // it's just part of the query, push possible syntax errors onto server's shoulders
                            } else {

                                escapeSequence = escapeSequence.substring(1, escapeSequence.length() - 1);
                                replaceEscapeSequence = true;
                            }
                        } catch (java.util.NoSuchElementException e) {
                            newSql.append(token); // it's just part of the query, push possible syntax errors onto server's shoulders
                        }
                    } else if (StringUtils.startsWithIgnoreCase(collapsedToken, "{fn")) {
                        int startPos = token.toLowerCase().indexOf("fn ") + 3;
                        int endPos = token.length() - 1; // no }

                        String fnToken = token.substring(startPos, endPos);

                        // We need to handle 'convert' by ourselves

                        if (StringUtils.startsWithIgnoreCaseAndWs(fnToken, "convert")) {
                            newSql.append(processConvertToken(fnToken));
                        } else {
                            // just pass functions right to the DB
                            newSql.append(fnToken);
                        }
                    } else if (StringUtils.startsWithIgnoreCase(collapsedToken, "{d")) {
                        int startPos = token.indexOf('\'') + 1;
                        int endPos = token.lastIndexOf('\''); // no }

                        if ((startPos == -1) || (endPos == -1)) {
                            newSql.append(token); // it's just part of the query, push possible syntax errors onto server's shoulders
                        } else {

                            String argument = token.substring(startPos, endPos);

                            try {
                                StringTokenizer st = new StringTokenizer(argument, " -");
                                String year4 = st.nextToken();
                                String month2 = st.nextToken();
                                String day2 = st.nextToken();
                                String dateString = "'" + year4 + "-" + month2 + "-" + day2 + "'";
                                newSql.append(dateString);
                            } catch (java.util.NoSuchElementException e) {
                            	throw new HeraSQLException("Not supported");
                            }
                        }
                    } else if (StringUtils.startsWithIgnoreCase(collapsedToken, "{call") || StringUtils.startsWithIgnoreCase(collapsedToken, "{?=call")) {

                        int startPos = StringUtils.indexOfIgnoreCase(token, "CALL") + 5;
                        int endPos = token.length() - 1;

                        if (StringUtils.startsWithIgnoreCase(collapsedToken, "{?=call")) {
                            callingStoredFunction = true;
                            newSql.append("SELECT ");
                            newSql.append(token.substring(startPos, endPos));
                        } else {
                            callingStoredFunction = false;
                            newSql.append("CALL ");
                            newSql.append(token.substring(startPos, endPos));
                        }

                        for (int i = endPos - 1; i >= startPos; i--) {
                            char c = token.charAt(i);

                            if (Character.isWhitespace(c)) {
                                continue;
                            }

                            if (c != ')') {
                                newSql.append("()"); // handle no-parenthesis no-arg call not supported by MySQL parser
                            }

                            break;
                        }
                    } else if (StringUtils.startsWithIgnoreCase(collapsedToken, "{oj")) {
                        // MySQL already handles this escape sequence because of ODBC. Cool.
                        newSql.append(token);
                    } else {
                        // not an escape code, just part of the query
                        newSql.append(token);
                    }
                } else {
                    newSql.append(token); // it's just part of the query
                }
            }
        }

        String escapedSql = newSql.toString();

        //
        // FIXME: Let MySQL do this, however requires lightweight parsing of statement
        //
        if (replaceEscapeSequence) {
            String currentSql = escapedSql;

            while (currentSql.indexOf(escapeSequence) != -1) {
                int escapePos = currentSql.indexOf(escapeSequence);
                String lhs = currentSql.substring(0, escapePos);
                String rhs = currentSql.substring(escapePos + 1, currentSql.length());
                currentSql = lhs + "\\" + rhs;
            }

            escapedSql = currentSql;
        }

        EscapeProcessorResult epr = new EscapeProcessorResult();
        epr.escapedSql = escapedSql;
        epr.callingStoredFunction = callingStoredFunction;

        if (usesVariables != USES_VARIABLES_TRUE) {
            if (escapeTokenizer.sawVariableUse()) {
                epr.usesVariables = USES_VARIABLES_TRUE;
            } else {
                epr.usesVariables = USES_VARIABLES_FALSE;
            }
        }

        return epr;
    }


    /**
     * Re-writes {fn convert (expr, type)} as cast(expr AS type)
     *
     * @param functionToken
     *            token
     * @param exceptionInterceptor
     *            exception interceptor
     * @return result of rewriting
     * @throws SQLException
     *             if error occurs
     */
    private static String processConvertToken(String functionToken) throws SQLException {
        // The JDBC spec requires these types:
        //
        // BIGINT
        // BINARY
        // BIT
        // CHAR
        // DATE
        // DECIMAL
        // DOUBLE
        // FLOAT
        // INTEGER
        // LONGVARBINARY
        // LONGVARCHAR
        // REAL
        // SMALLINT
        // TIME
        // TIMESTAMP
        // TINYINT
        // VARBINARY
        // VARCHAR

        // MySQL supports these types:
        //
        // BINARY
        // CHAR
        // DATE
        // DATETIME
        // SIGNED (integer)
        // UNSIGNED (integer)
        // TIME

        int firstIndexOfParen = functionToken.indexOf("(");

        if (firstIndexOfParen == -1) {
        	throw new HeraSQLException("Not supported");
        }

        int indexOfComma = functionToken.lastIndexOf(",");

        if (indexOfComma == -1) {
        	throw new HeraSQLException("Not supported");
        }

        int indexOfCloseParen = functionToken.indexOf(')', indexOfComma);

        if (indexOfCloseParen == -1) {
        	throw new HeraSQLException("Not supported");

        }

        String expression = functionToken.substring(firstIndexOfParen + 1, indexOfComma);
        String type = functionToken.substring(indexOfComma + 1, indexOfCloseParen);

        String newType = null;

        String trimmedType = type.trim();

        if (StringUtils.startsWithIgnoreCase(trimmedType, "SQL_")) {
            trimmedType = trimmedType.substring(4, trimmedType.length());
        }

        newType = JDBC_CONVERT_TO_MYSQL_TYPE_MAP.get(trimmedType.toUpperCase(Locale.ENGLISH));

        if (newType == null) {
        	throw new HeraSQLException("Not supported");
        }

        int replaceIndex = newType.indexOf("?");

        if (replaceIndex != -1) {
            StringBuilder convertRewrite = new StringBuilder(newType.substring(0, replaceIndex));
            convertRewrite.append(expression);
            convertRewrite.append(newType.substring(replaceIndex + 1, newType.length()));

            return convertRewrite.toString();
        }

        StringBuilder castRewrite = new StringBuilder("CAST(");
        castRewrite.append(expression);
        castRewrite.append(" AS ");
        castRewrite.append(newType);
        castRewrite.append(")");

        return castRewrite.toString();

    }

    /**
     * Removes all whitespace from the given String. We use this to make escape
     * token comparison white-space ignorant.
     *
     * @param toCollapse
     *            the string to remove the whitespace from
     *
     * @return a string with _no_ whitespace.
     */
    private static String removeWhitespace(String toCollapse) {
        if (toCollapse == null) {
            return null;
        }

        int length = toCollapse.length();

        StringBuilder collapsed = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            char c = toCollapse.charAt(i);

            if (!Character.isWhitespace(c)) {
                collapsed.append(c);
            }
        }

        return collapsed.toString();
    }
}