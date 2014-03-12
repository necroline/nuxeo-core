/*
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 * $Id$
 */

package org.nuxeo.ecm.core.api.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.ComponentName;
import org.nuxeo.runtime.model.DefaultComponent;

/**
 * High-level service to get to repositories and from there to CoreSession
 * objects.
 */
public class RepositoryManagerImpl extends DefaultComponent implements
        RepositoryManager {

    private static final Log log = LogFactory.getLog(RepositoryManagerImpl.class);

    public static final String XP_REPOSITORIES = "repositories";

    private Map<String, Repository> repositories = Collections.synchronizedMap(new LinkedHashMap<String, Repository>());

    @Override
    public void addRepository(Repository repository) {
        String repoName = repository.getName();
        if (repositories.containsKey(repoName)) {
            log.info("Overriding repository: " + repoName);
        } else {
            log.info("Registering repository: " + repoName);
        }
        repositories.put(repoName, repository);
    }

    @Override
    public Collection<Repository> getRepositories() {
        return new ArrayList<Repository>(repositories.values());
    }

    @Override
    public List<String> getRepositoryNames() {
        return new ArrayList<String>(repositories.keySet());
    }

    @Override
    public Repository getRepository(String name) {
        return repositories.get(name);
    }

    @Override
    public void removeRepository(String name) {
        log.info("Removing repository: " + name);
        repositories.remove(name);
    }

    @Override
    public void clear() {
        repositories.clear();
    }

    @Override
    public Repository getDefaultRepository() {
        Iterator<Repository> it = repositories.values().iterator();

        Repository defaultRepo = null;

        // search for user defined
        while (it.hasNext()) {
            Repository repo = it.next();
            if (repo.isDefault()) {
                return repo;
            }
            if ("default".equals(repo.getName())) {
                defaultRepo = repo;
            }
        }

        // "default" fallback
        if (defaultRepo != null) {
            return defaultRepo;
        }

        // first in list "fallback"
        if (!repositories.isEmpty()) {
            return repositories.values().iterator().next();
        }

        // no repository at all
        return null;
    }

    @Override
    public void activate(ComponentContext context) throws Exception {
        repositories.clear();
    }

    @Override
    public void deactivate(ComponentContext context) throws Exception {
        repositories.clear();
    }

    @Override
    public void registerContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor) {
        if (XP_REPOSITORIES.equals(extensionPoint)) {
            addRepository((Repository) contribution);
        } else {
            throw new RuntimeException("Unknown extension point: "
                    + extensionPoint);
        }
    }

    @Override
    public void unregisterContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor) {
        if (XP_REPOSITORIES.equals(extensionPoint)) {
            removeRepository(((Repository) contribution).getName());
        } else {
            throw new RuntimeException("Unknown extension point: "
                    + extensionPoint);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAdapter(Class<T> adapter) {
        if (RepositoryManager.class.isAssignableFrom(adapter)) {
            return (T) this;
        }
        return null;
    }

}
