/*
 * Copyright (c) 2006-2012 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bogdan Stefanescu
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.schema.types.primitives;

import org.nuxeo.ecm.core.schema.types.PrimitiveType;

/**
 * The Boolean type.
 */
public final class BooleanType extends PrimitiveType {

    private static final long serialVersionUID = 1L;

    public static final String ID = "boolean";

    public static final BooleanType INSTANCE = new BooleanType();

    private BooleanType() {
        super(ID);
    }

    @Override
    public boolean validate(Object object) {
        return object instanceof Boolean;
    }

    @Override
    public Object convert(Object value) {
        if (value instanceof Boolean) {
            return value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue() != 0 ? Boolean.TRUE
                    : Boolean.FALSE;
        } else {
            return Boolean.valueOf((String) value);
        }
    }

    @Override
    public Object decode(String str) {
        if (str == null) {
            return null;
        }
        return Boolean.valueOf(str);
    }

    @Override
    public String encode(Object value) {

        if (value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof Number) {
            return ((Number) value).intValue() != 0 ? Boolean.TRUE.toString()
                    : Boolean.FALSE.toString();
        } else {
            return value != null ? (String) value : "";
        }
    }

    @Override
    public Object newInstance() {
        return Boolean.FALSE;
    }

    protected Object readResolve() {
        return INSTANCE;
    }

}
