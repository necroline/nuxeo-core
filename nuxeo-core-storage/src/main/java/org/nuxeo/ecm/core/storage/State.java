/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.storage;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Abstraction for a Map<String, Serializable> that is Serializable.
 *
 * @since 5.9.5
 */
public class State implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final int HASHMAP_DEFAULT_INITIAL_CAPACITY = 16;

    private static final float HASHMAP_DEFAULT_LOAD_FACTOR = 0.75f;

    private static final int DEBUG_MAX_STRING = 100;

    private static final int DEBUG_MAX_ARRAY = 10;

    /** Initial key order for the {@link #toString} method. */
    private static final Set<String> TO_STRING_KEY_ORDER = new LinkedHashSet<>(
            Arrays.asList(new String[] { "ecm:id", "ecm:primaryType",
                    "ecm:name", "ecm:parentId", "ecm:isVersion", "ecm:isProxy" }));

    /**
     * A {@link State} actually containing a diff.
     * <p>
     * Values can be regular values, or:
     * <ul>
     * <li>a sub-{@link Diff},
     * <li>an array or List containing a {@link DiffOp} as its first element.
     * </ul>
     *
     * @since 5.9.5
     */
    public static class Diff extends State {

        private static final long serialVersionUID = 1L;

    }

    /**
     * Diff operation markers for arrays or Lists.
     *
     * @since 5.9.5
     */
    public static enum DiffOp {
        /**
         * When used as the first element of an array or List, means that the
         * rest of the array/list should be appended to the right of the
         * existing array/List.
         */
        RPUSH,

        /**
         * When used as an array or List, means that one element should be
         * removed from the right of the array/List.
         */
        RPOP
    }

    protected final Map<String, Serializable> map;

    /**
     * Constructor with default capacity.
     */
    public State() {
        map = new HashMap<>();
    }

    /**
     * Constructor for a given default size.
     */
    public State(int size) {
        map = new HashMap<>(Math.max(
                (int) (size / HASHMAP_DEFAULT_LOAD_FACTOR) + 1,
                HASHMAP_DEFAULT_INITIAL_CAPACITY), HASHMAP_DEFAULT_LOAD_FACTOR);
    }

    /**
     * Gets the number of elements.
     */
    public int size() {
        return map.size();
    }

    /**
     * Checks if the state is empty.
     */
    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * Gets a value for a key, or {@code null} if the key is not present.
     */
    public Serializable get(Object key) {
        return map.get(key);
    }

    /**
     * Sets a key/value.
     */
    public Serializable put(String key, Serializable value) {
        return map.put(key, value);
    }

    /**
     * Sets all the key/values from the passed {@link State}.
     */
    public void putAll(State state) {
        map.putAll(state.map);
    }

    /**
     * Removes the mapping for a key.
     *
     * @return the previous value associated with the key, or {@code null} if
     *         there was no mapping for the key
     */
    public Serializable remove(Object key) {
        return map.remove(key);
    }

    /**
     * Gets the key set. IT MUST NOT BE MODIFIED.
     */
    public Set<String> keySet() {
        return map.keySet();
    }

    /**
     * Checks if there is a mapping for the given key.
     */
    public boolean containsKey(Object key) {
        return map.containsKey(key);
    }

    /**
     * Gets the entry set. IT MUST NOT BE MODIFIED.
     */
    public Set<Entry<String, Serializable>> entrySet() {
        return map.entrySet();
    }

    /**
     * Overridden to display Calendars and arrays better, and truncate long
     * strings and arrays.
     * <p>
     * Also displays some keys first (ecm:id, ecm:name, ecm:primaryType)
     */
    @Override
    public String toString() {
        if (isEmpty()) {
            return "{}";
        }
        StringBuilder buf = new StringBuilder();
        buf.append('{');
        boolean empty = true;
        // some keys go first
        for (String key : TO_STRING_KEY_ORDER) {
            if (containsKey(key)) {
                if (!empty) {
                    buf.append(", ");
                }
                empty = false;
                buf.append(key);
                buf.append('=');
                toString(buf, get(key));
            }
        }
        // sort keys
        String[] keys = keySet().toArray(new String[0]);
        Arrays.sort(keys);
        for (String key : keys) {
            if (TO_STRING_KEY_ORDER.contains(key)) {
                // already done
                continue;
            }
            if (!empty) {
                buf.append(", ");
            }
            empty = false;
            buf.append(key);
            buf.append('=');
            toString(buf, get(key));
        }
        buf.append('}');
        return buf.toString();
    }

    @SuppressWarnings("boxing")
    protected static void toString(StringBuilder buf, Object value) {
        if (value instanceof String) {
            String v = (String) value;
            if (v.length() > DEBUG_MAX_STRING) {
                v = v.substring(0, DEBUG_MAX_STRING) + "...(" + v.length()
                        + " chars)...";
            }
            buf.append(v);
        } else if (value instanceof Calendar) {
            Calendar cal = (Calendar) value;
            char sign;
            int offset = cal.getTimeZone().getOffset(cal.getTimeInMillis()) / 60000;
            if (offset < 0) {
                offset = -offset;
                sign = '-';
            } else {
                sign = '+';
            }
            buf.append(String.format(
                    "Calendar(%04d-%02d-%02dT%02d:%02d:%02d.%03d%c%02d:%02d)",
                    cal.get(Calendar.YEAR), //
                    cal.get(Calendar.MONTH) + 1, //
                    cal.get(Calendar.DAY_OF_MONTH), //
                    cal.get(Calendar.HOUR_OF_DAY), //
                    cal.get(Calendar.MINUTE), //
                    cal.get(Calendar.SECOND), //
                    cal.get(Calendar.MILLISECOND), //
                    sign, offset / 60, offset % 60));
        } else if (value instanceof Object[]) {
            Object[] v = (Object[]) value;
            buf.append('[');
            for (int i = 0; i < v.length; i++) {
                if (i > 0) {
                    buf.append(',');
                    if (i > DEBUG_MAX_ARRAY) {
                        buf.append("...(" + v.length + " items)...");
                        break;
                    }
                }
                toString(buf, v[i]);
            }
            buf.append(']');
        } else {
            buf.append(value);
        }
    }

}