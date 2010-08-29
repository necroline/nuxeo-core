/*
 * (C) Copyright 2010 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     mcedica
 */
package org.nuxeo.ecm.core.management.statuses;

import org.nuxeo.runtime.api.Framework;

/**
 * Retrieves the administrative status of the server.
 *
 * @author Mariana Cedica
 */
public class AdministrativeStatusProbe implements Probe {

     public void init(Object service) {
     }

    public ProbeStatus run()  {
        AdministrativeStatus adm = Framework.getLocalService(AdministrativeStatus.class);
        String info = format(adm);
        if (!adm.isActive()) {
            return ProbeStatus.newFailure(info);
        }
        return ProbeStatus.newSuccess(info);
     }

    protected static String format(AdministrativeStatus status) {
        return "<span class=\"server\">" + status.getServerInstanceName() + "</span> is <span class=\"value\">" + status.getValue() + "</span>";
     }


}
