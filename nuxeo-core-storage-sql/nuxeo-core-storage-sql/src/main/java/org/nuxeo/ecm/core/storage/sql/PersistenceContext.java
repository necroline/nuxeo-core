/*
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.storage.sql;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.collections.map.ReferenceMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.schema.FacetNames;
import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.Fragment.State;
import org.nuxeo.ecm.core.storage.sql.Invalidations.InvalidationsPair;
import org.nuxeo.ecm.core.storage.sql.RowMapper.CopyResult;
import org.nuxeo.ecm.core.storage.sql.RowMapper.IdWithTypes;
import org.nuxeo.ecm.core.storage.sql.RowMapper.RowBatch;
import org.nuxeo.ecm.core.storage.sql.RowMapper.RowUpdate;
import org.nuxeo.ecm.core.storage.sql.SimpleFragment.FieldComparator;

/**
 * This class holds persistence context information.
 * <p>
 * All non-saved modified data is referenced here. At save time, the data is
 * sent to the database by the {@link Mapper}. The database will at some time
 * later be committed by the external transaction manager in effect.
 * <p>
 * Internally a fragment can be in at most one of the "pristine" or "modified"
 * map. After a save() all the fragments are pristine, and may be partially
 * invalidated after commit by other local or clustered contexts that committed
 * too.
 * <p>
 * Depending on the table, the context may hold {@link SimpleFragment}s, which
 * represent one row, {@link CollectionFragment}s, which represent several rows.
 * <p>
 * This class is not thread-safe, it should be tied to a single session and the
 * session itself should not be used concurrently.
 */
public class PersistenceContext {

    private static final Log log = LogFactory.getLog(PersistenceContext.class);

    private static final FieldComparator POS_COMPARATOR = new FieldComparator(
            Model.HIER_CHILD_POS_KEY);

    private static final FieldComparator VER_CREATED_COMPARATOR = new FieldComparator(
            Model.VERSION_CREATED_KEY);

    protected final Model model;

    // protected because accessed by Fragment.refetch()
    protected final RowMapper mapper;

    private final SessionImpl session;

    // selection context for complex properties
    private final SelectionContext hierComplex;

    // selection context for non-complex properties
    // public because used by unit tests
    public final SelectionContext hierNonComplex;

    // selection context for versions by series
    private final SelectionContext seriesVersions;

    // selection context for proxies by series
    private final SelectionContext seriesProxies;

    // selection context for proxies by target
    private final SelectionContext targetProxies;

    private final SelectionContext[] selections;

    /**
     * The pristine fragments. All held data is identical to what is present in
     * the database and could be refetched if needed.
     * <p>
     * This contains fragment that are {@link State#PRISTINE} or
     * {@link State#ABSENT}, or in some cases {@link State#INVALIDATED_MODIFIED}
     * or {@link State#INVALIDATED_DELETED}.
     * <p>
     * Pristine fragments must be kept here when referenced by the application,
     * because the application must get the same fragment object if asking for
     * it twice, even in two successive transactions.
     * <p>
     * This is memory-sensitive, a fragment can always be refetched if nobody
     * uses it and the GC collects it. Use a weak reference for the values, we
     * don't hold them longer than they need to be referenced, as the underlying
     * mapper also has its own cache.
     */
    protected final Map<RowId, Fragment> pristine;

    /**
     * The fragments changed by the session.
     * <p>
     * This contains fragment that are {@link State#CREATED},
     * {@link State#MODIFIED} or {@link State#DELETED}.
     */
    protected final Map<RowId, Fragment> modified;

    /**
     * Fragment ids generated but not yet saved. We know that any fragment with
     * one of these ids cannot exist in the database.
     */
    private final Set<Serializable> createdIds;

    private boolean isAllowedDeleteNonHierarchyFragments = true;

    @SuppressWarnings("unchecked")
    public PersistenceContext(Model model, RowMapper mapper, SessionImpl session)
            throws StorageException {
        this.model = model;
        this.mapper = mapper;
        this.session = session;
        hierComplex = new SelectionContext(SelectionType.CHILDREN,
                Boolean.TRUE, mapper, this);
        hierNonComplex = new SelectionContext(SelectionType.CHILDREN,
                Boolean.FALSE, mapper, this);
        seriesVersions = new SelectionContext(SelectionType.SERIES_VERSIONS,
                null, mapper, this);
        seriesProxies = new SelectionContext(SelectionType.SERIES_PROXIES,
                null, mapper, this);
        targetProxies = new SelectionContext(SelectionType.TARGET_PROXIES,
                null, mapper, this);
        selections = new SelectionContext[] { hierComplex, hierNonComplex,
                seriesVersions, seriesProxies, targetProxies };

        // use a weak reference for the values, we don't hold them longer than
        // they need to be referenced, as the underlying mapper also has its own
        // cache
        pristine = new ReferenceMap(ReferenceMap.HARD, ReferenceMap.WEAK);
        modified = new HashMap<RowId, Fragment>();
        // this has to be linked to keep creation order, as foreign keys
        // are used and need this
        createdIds = new LinkedHashSet<Serializable>();
        isAllowedDeleteNonHierarchyFragments = detectAllowedDeleteNonHierarchyFragments();
    }

    protected boolean detectAllowedDeleteNonHierarchyFragments() {
        String value = System.getProperty("nuxeo.vcs.isAllowedDeleteNonHierarchyFragments");
        if (value == null) {
            return true;
        }
        return Boolean.parseBoolean(value);
    }

    protected int clearCaches() {
        mapper.clearCache();
        for (SelectionContext sel : selections) {
            sel.clearCaches();
        }
        // TODO there should be a synchronization here
        // but this is a rare operation and we don't call
        // it if a transaction is in progress
        int n = pristine.size();
        pristine.clear();
        modified.clear(); // not empty when rolling back before save
        createdIds.clear();
        return n;
    }

    /**
     * Generates a new id, or used a pre-generated one (import).
     */
    protected Serializable generateNewId(Serializable id) {
        if (id == null) {
            id = model.generateNewId();
        }
        createdIds.add(id);
        return id;
    }

    protected boolean isIdNew(Serializable id) {
        return createdIds.contains(id);
    }

    /**
     * Saves all the created, modified and deleted rows into a batch object, for
     * later execution.
     */
    protected RowBatch getSaveBatch() throws StorageException {
        RowBatch batch = new RowBatch();

        // created main rows are saved first in the batch (in their order of
        // creation), because they are used as foreign keys in all other tables
        for (Serializable id : createdIds) {
            RowId rowId = new RowId(model.HIER_TABLE_NAME, id);
            Fragment fragment = modified.remove(rowId);
            if (fragment == null) {
                // was created and deleted before save
                continue;
            }
            batch.creates.add(fragment.row);
            fragment.clearDirty();
            fragment.setPristine();
            pristine.put(rowId, fragment);
        }
        createdIds.clear();

        // save the rest
        for (Entry<RowId, Fragment> en : modified.entrySet()) {
            RowId rowId = en.getKey();
            Fragment fragment = en.getValue();
            switch (fragment.getState()) {
            case CREATED:
                batch.creates.add(fragment.row);
                fragment.clearDirty();
                fragment.setPristine();
                // modified map cleared at end of loop
                pristine.put(rowId, fragment);
                break;
            case MODIFIED:
                if (fragment.row.isCollection()) {
                    if (((CollectionFragment) fragment).isDirty()) {
                        batch.updates.add(new RowUpdate(fragment.row, null));
                        fragment.clearDirty();
                    }
                } else {
                    Collection<String> keys = ((SimpleFragment) fragment).getDirtyKeys();
                    if (!keys.isEmpty()) {
                        batch.updates.add(new RowUpdate(fragment.row, keys));
                        fragment.clearDirty();
                    }
                }
                fragment.setPristine();
                // modified map cleared at end of loop
                pristine.put(rowId, fragment);
                break;
            case DELETED:
                // TODO deleting non-hierarchy fragments is done by the database
                // itself as their foreign key to hierarchy is ON DELETE CASCADE
                batch.deletes.add(new RowId(rowId));
                fragment.setDetached();
                // modified map cleared at end of loop
                break;
            case PRISTINE:
                // cannot happen, but has been observed :(
                log.error("Found PRISTINE fragment in modified map: "
                        + fragment);
                break;
            default:
                throw new RuntimeException(fragment.toString());
            }
        }
        modified.clear();

        // flush selections caches
        for (SelectionContext sel : selections) {
            sel.postSave();
        }

        return batch;
    }

    private boolean complexProp(SimpleFragment fragment)
            throws StorageException {
        return ((Boolean) fragment.get(model.HIER_CHILD_ISPROPERTY_KEY)).booleanValue();
    }

    private SelectionContext getHierSelectionContext(boolean complexProp) {
        return complexProp ? hierComplex : hierNonComplex;
    }

    /**
     * Finds the documents having dirty text or dirty binaries that have to be
     * reindexed as fulltext.
     *
     * @param dirtyStrings set of ids, updated by this method
     * @param dirtyBinaries set of ids, updated by this method
     */
    protected void findDirtyDocuments(Set<Serializable> dirtyStrings,
            Set<Serializable> dirtyBinaries) throws StorageException {
        for (Fragment fragment : modified.values()) {
            Serializable docId = null;
            switch (fragment.getState()) {
            case CREATED:
                docId = getContainingDocument(fragment.getId());
                dirtyStrings.add(docId);
                dirtyBinaries.add(docId);
                break;
            case MODIFIED:
                String tableName = fragment.row.tableName;
                Collection<String> keys;
                if (model.isCollectionFragment(tableName)) {
                    keys = Collections.singleton(null);
                } else {
                    keys = ((SimpleFragment) fragment).getDirtyKeys();
                }
                for (String key : keys) {
                    PropertyType type = model.getFulltextFieldType(tableName,
                            key);
                    if (type == null) {
                        continue;
                    }
                    if (docId == null) {
                        docId = getContainingDocument(fragment.getId());
                    }
                    if (type == PropertyType.STRING) {
                        dirtyStrings.add(docId);
                    } else if (type == PropertyType.BINARY) {
                        dirtyBinaries.add(docId);
                    }
                }
                break;
            case DELETED:
                docId = getContainingDocument(fragment.getId());
                if (!isDeleted(docId)) {
                    // this is a deleted fragment of a complex property from a
                    // document that has not been completely deleted
                    dirtyStrings.add(docId);
                    dirtyBinaries.add(docId);
                }
                break;
            default:
            }
        }
    }

    /**
     * Marks locally all the invalidations gathered by a {@link Mapper}
     * operation (like a version restore).
     */
    protected void markInvalidated(Invalidations invalidations) {
        if (invalidations.modified != null) {
            for (RowId rowId : invalidations.modified) {
                Fragment fragment = getIfPresent(rowId);
                if (fragment != null) {
                    setFragmentPristine(fragment);
                    fragment.setInvalidatedModified();
                }
            }
            for (SelectionContext sel : selections) {
                sel.markInvalidated(invalidations.modified);
            }
        }
        if (invalidations.deleted != null) {
            for (RowId rowId : invalidations.deleted) {
                Fragment fragment = getIfPresent(rowId);
                if (fragment != null) {
                    setFragmentPristine(fragment);
                    fragment.setInvalidatedDeleted();
                }
            }
        }
        // TODO XXX transactionInvalidations.add(invalidations);
    }

    // called from Fragment
    protected void setFragmentModified(Fragment fragment) {
        RowId rowId = fragment.row;
        pristine.remove(rowId);
        modified.put(rowId, fragment);
    }

    // also called from Fragment
    protected void setFragmentPristine(Fragment fragment) {
        RowId rowId = fragment.row;
        modified.remove(rowId);
        pristine.put(rowId, fragment);
    }

    /**
     * Post-transaction invalidations notification.
     * <p>
     * Called post-transaction by session commit/rollback or transactionless
     * save.
     */
    public void sendInvalidationsToOthers() throws StorageException {
        Invalidations invalidations = new Invalidations();
        for (SelectionContext sel : selections) {
            sel.gatherInvalidations(invalidations);
        }
        mapper.sendInvalidations(invalidations);
        // events sent in mapper
    }

    /**
     * Applies all invalidations accumulated.
     * <p>
     * Called pre-transaction by start or transactionless save;
     */
    public void processReceivedInvalidations() throws StorageException {
        InvalidationsPair invals = mapper.receiveInvalidations();
        if (invals == null) {
            return;
        }

        processCacheInvalidations(invals.cacheInvalidations);

        session.sendInvalidationEvent(invals);
    }

    public void processCacheInvalidations(Invalidations invalidations)
            throws StorageException {
        if (invalidations == null) {
            return;
        }
        if (invalidations.modified != null) {
            for (RowId rowId : invalidations.modified) {
                Fragment fragment = pristine.remove(rowId);
                if (fragment != null) {
                    fragment.setInvalidatedModified();
                }
            }
            for (SelectionContext sel : selections) {
                sel.processReceivedInvalidations(invalidations.modified);
            }
        }
        if (invalidations.deleted != null) {
            for (RowId rowId : invalidations.deleted) {
                Fragment fragment = pristine.remove(rowId);
                if (fragment != null) {
                    fragment.setInvalidatedDeleted();
                }
            }
        }
    }

    public void checkInvalidationsConflict() {
        // synchronized (receivedInvalidations) {
        // if (receivedInvalidations.modified != null) {
        // for (RowId rowId : receivedInvalidations.modified) {
        // if (transactionInvalidations.contains(rowId)) {
        // throw new ConcurrentModificationException(
        // "Updating a concurrently modified value: "
        // + new RowId(rowId));
        // }
        // }
        // }
        //
        // if (receivedInvalidations.deleted != null) {
        // for (RowId rowId : receivedInvalidations.deleted) {
        // if (transactionInvalidations.contains(rowId)) {
        // throw new ConcurrentModificationException(
        // "Updating a concurrently deleted value: "
        // + new RowId(rowId));
        // }
        // }
        // }
        // }
    }

    /**
     * Gets a fragment, if present in the context.
     * <p>
     * Called by {@link #get}, and by the {@link Mapper} to reuse known
     * selection fragments.
     *
     * @param rowId the fragment id
     * @return the fragment, or {@code null} if not found
     */
    protected Fragment getIfPresent(RowId rowId) {
        Fragment fragment = pristine.get(rowId);
        if (fragment != null) {
            return fragment;
        }
        return modified.get(rowId);
    }

    /**
     * Gets a fragment.
     * <p>
     * If it's not in the context, fetch it from the mapper. If it's not in the
     * database, returns {@code null} or an absent fragment.
     * <p>
     * Deleted fragments may be returned.
     *
     * @param rowId the fragment id
     * @param allowAbsent {@code true} to return an absent fragment as an object
     *            instead of {@code null}
     * @return the fragment, or {@code null} if none is found and {@value
     *         allowAbsent} was {@code false}
     */
    protected Fragment get(RowId rowId, boolean allowAbsent)
            throws StorageException {
        Fragment fragment = getIfPresent(rowId);
        if (fragment == null) {
            fragment = getFromMapper(rowId, allowAbsent);
        }
        // if (fragment != null && fragment.getState() == State.DELETED) {
        // fragment = null;
        // }
        return fragment;
    }

    protected Fragment getFromMapper(RowId rowId, boolean allowAbsent)
            throws StorageException {
        List<Fragment> fragments = getFromMapper(Collections.singleton(rowId),
                allowAbsent);
        return fragments.isEmpty() ? null : fragments.get(0);
    }

    /**
     * Gets a collection of fragments from the mapper. No order is kept between
     * the inputs and outputs.
     * <p>
     * Fragments not found are not returned if {@code allowAbsent} is
     * {@code false}.
     */
    protected List<Fragment> getFromMapper(Collection<RowId> rowIds,
            boolean allowAbsent) throws StorageException {
        List<Fragment> res = new ArrayList<Fragment>(rowIds.size());

        // find fragments we really want to fetch
        List<RowId> todo = new ArrayList<RowId>(rowIds.size());
        for (RowId rowId : rowIds) {
            if (isIdNew(rowId.id)) {
                // the id has not been saved, so nothing exists yet in the
                // database
                // rowId is not a row -> will use an absent fragment
                Fragment fragment = getFragmentFromFetchedRow(rowId,
                        allowAbsent);
                if (fragment != null) {
                    res.add(fragment);
                }
            } else {
                todo.add(rowId);
            }
        }
        if (todo.isEmpty()) {
            return res;
        }

        // fetch these fragments in bulk
        List<? extends RowId> rows = mapper.read(todo);
        res.addAll(getFragmentsFromFetchedRows(rows, allowAbsent));

        return res;
    }

    /**
     * Gets a list of fragments.
     * <p>
     * If a fragment is not in the context, fetch it from the mapper. If it's
     * not in the database, use an absent fragment or skip it.
     * <p>
     * Deleted fragments are skipped.
     *
     * @param id the fragment id
     * @param allowAbsent {@code true} to return an absent fragment as an object
     *            instead of skipping it
     * @return the fragments, in arbitrary order (no {@code null}s)
     */
    public List<Fragment> getMulti(Collection<RowId> rowIds, boolean allowAbsent)
            throws StorageException {
        if (rowIds.isEmpty()) {
            return Collections.emptyList();
        }

        // find those already in the context
        List<Fragment> res = new ArrayList<Fragment>(rowIds.size());
        List<RowId> todo = new LinkedList<RowId>();
        for (RowId rowId : rowIds) {
            Fragment fragment = getIfPresent(rowId);
            if (fragment == null) {
                todo.add(rowId);
            } else {
                if (fragment.getState() != State.DELETED) {
                    res.add(fragment);
                }
            }
        }
        if (todo.isEmpty()) {
            return res;
        }

        // fetch missing ones, return union
        List<Fragment> fetched = getFromMapper(todo, allowAbsent);
        res.addAll(fetched);
        return res;
    }

    /**
     * Turns the given rows (just fetched from the mapper) into fragments and
     * record them in the context.
     * <p>
     * For each row, if the context already contains a fragment with the given
     * id, it is returned instead of building a new one.
     * <p>
     * Deleted fragments are skipped.
     * <p>
     * If a simple {@link RowId} is passed, it means that an absent row was
     * found by the mapper. An absent fragment will be returned, unless
     * {@code allowAbsent} is {@code false} in which case it will be skipped.
     *
     * @param rowIds the list of rows or row ids
     * @param allowAbsent {@code true} to return an absent fragment as an object
     *            instead of {@code null}
     * @return the list of fragments
     */
    protected List<Fragment> getFragmentsFromFetchedRows(
            List<? extends RowId> rowIds, boolean allowAbsent)
            throws StorageException {
        List<Fragment> fragments = new ArrayList<Fragment>(rowIds.size());
        for (RowId rowId : rowIds) {
            Fragment fragment = getFragmentFromFetchedRow(rowId, allowAbsent);
            if (fragment != null) {
                fragments.add(fragment);
            }
        }
        return fragments;
    }

    /**
     * Turns the given row (just fetched from the mapper) into a fragment and
     * record it in the context.
     * <p>
     * If the context already contains a fragment with the given id, it is
     * returned instead of building a new one.
     * <p>
     * If the fragment was deleted, {@code null} is returned.
     * <p>
     * If a simple {@link RowId} is passed, it means that an absent row was
     * found by the mapper. An absent fragment will be returned, unless
     * {@code allowAbsent} is {@code false} in which case {@code null} will be
     * returned.
     *
     * @param rowId the row or row id (may be {@code null})
     * @param allowAbsent {@code true} to return an absent fragment as an object
     *            instead of {@code null}
     * @return the fragment, or {@code null} if it was deleted
     */
    protected Fragment getFragmentFromFetchedRow(RowId rowId,
            boolean allowAbsent) throws StorageException {
        if (rowId == null) {
            return null;
        }
        Fragment fragment = getIfPresent(rowId);
        if (fragment != null) {
            // row is already known in the context, use it
            State state = fragment.getState();
            if (state == State.DELETED) {
                // row has been deleted in the context, ignore it
                return null;
            } else if (state == State.INVALIDATED_MODIFIED
                    || state == State.INVALIDATED_DELETED) {
                // XXX TODO
                throw new IllegalStateException(state.toString());
            } else {
                // keep existing fragment
                return fragment;
            }
        }
        boolean isCollection = model.isCollectionFragment(rowId.tableName);
        if (rowId instanceof Row) {
            Row row = (Row) rowId;
            if (isCollection) {
                fragment = new CollectionFragment(row, State.PRISTINE, this);
            } else {
                fragment = new SimpleFragment(row, State.PRISTINE, this);
                for (SelectionContext sel : selections) {
                    sel.recordExisting((SimpleFragment) fragment, false);
                }
            }
            return fragment;
        } else {
            if (allowAbsent) {
                if (isCollection) {
                    Serializable[] empty = model.getCollectionFragmentType(
                            rowId.tableName).getEmptyArray();
                    Row row = new Row(rowId.tableName, rowId.id, empty);
                    return new CollectionFragment(row, State.ABSENT, this);
                } else {
                    Row row = new Row(rowId.tableName, rowId.id);
                    return new SimpleFragment(row, State.ABSENT, this);
                }
            } else {
                return null;
            }
        }
    }

    public SimpleFragment createHierarchyFragment(Row row)
            throws StorageException {
        SimpleFragment fragment = createSimpleFragment(row);
        hierComplex.recordCreated(fragment);
        hierNonComplex.recordCreated(fragment);
        // no children for this new node
        Serializable id = fragment.getId();
        hierComplex.newSelection(id);
        hierNonComplex.newSelection(id);
        // could add to seriesProxies and seriesVersions as well
        return fragment;
    }

    private SimpleFragment createVersionFragment(Row row)
            throws StorageException {
        SimpleFragment fragment = createSimpleFragment(row);
        seriesVersions.recordCreated(fragment);
        // no proxies for this new version
        targetProxies.newSelection(fragment.getId());
        return fragment;
    }

    public void createdProxyFragment(SimpleFragment fragment)
            throws StorageException {
        seriesProxies.recordCreated(fragment);
        targetProxies.recordCreated(fragment);
    }

    public void removedProxyTarget(SimpleFragment fragment)
            throws StorageException {
        targetProxies.recordRemoved(fragment);
    }

    public void addedProxyTarget(SimpleFragment fragment)
            throws StorageException {
        targetProxies.recordCreated(fragment);
    }

    private SimpleFragment createSimpleFragment(Row row)
            throws StorageException {
        if (pristine.containsKey(row) || modified.containsKey(row)) {
            throw new StorageException("Row already registered: " + row);
        }
        return new SimpleFragment(row, State.CREATED, this);
    }

    public void removeNode(SimpleFragment hierFragment) throws StorageException {
        Serializable id = hierFragment.getId();

        if (hierFragment.getState() == State.CREATED) {
            // only case where we can recurse in children,
            // it's safe to do as they're in memory as well
            for (SimpleFragment f : getChildren(id, null, true)) {
                removeNode(f);
            }
            for (SimpleFragment f : getChildren(id, null, false)) {
                removeNode(f);
            }
        }
        // We cannot recursively delete the children from the cache as we don't
        // know all their ids and it would be costly to obtain them. Instead we
        // do a check on getNodeById using isDeleted() to see if there's a
        // deleted parent.

        // TODO must recurse, to get all children in order to remove
        // them from selections

        // make sure we really remove proxy and version fragments (to
        // update selections) even if they aren't in memory caches
        // so that selections get updated
        boolean isProxy = model.PROXY_TYPE.equals(hierFragment.get(model.MAIN_PRIMARY_TYPE_KEY));
        if (isProxy) {
            Fragment fragment = get(new RowId(model.PROXY_TABLE_NAME, id), true);
            removeFragment(fragment);
        }
        boolean isVersion = Boolean.TRUE.equals(hierFragment.get(model.MAIN_IS_VERSION_KEY));
        Serializable versionSeriesId;
        if (isVersion) {
            SimpleFragment fragment = (SimpleFragment) get(new RowId(
                    model.VERSION_TABLE_NAME, id), true);
            versionSeriesId = fragment.get(model.VERSION_VERSIONABLE_KEY);
            removeFragment(fragment);
        } else {
            versionSeriesId = null;
        }

        // remove the lock using the lock manager
        session.removeLock(id, null, false);

        // remove the hierarchy fragment
        removeFragment(hierFragment);

        // for versions there's stuff we have to recompute
        if (versionSeriesId != null) {
            recomputeVersionSeries(versionSeriesId);
        }

        if (!isAllowedDeleteNonHierarchyFragments) {
            return;
        }

        // find all the fragments with this id in the maps
        List<Fragment> fragments = new LinkedList<Fragment>();
        for (Fragment fragment : pristine.values()) {
            if (id.equals(fragment.getId())) {
                fragments.add(fragment);
            }
        }
        for (Fragment fragment : modified.values()) {
            if (id.equals(fragment.getId())) {
                if (fragment.getState() != State.DELETED) {
                    fragments.add(fragment);
                }
            }
        }
        // remove the fragments
        for (Fragment fragment : fragments) {
            removeFragment(fragment);
        }
    }

    /** Deletes a fragment from the context. */
    public void removeFragment(Fragment fragment) throws StorageException {
        if (fragment instanceof SimpleFragment) {
            for (SelectionContext sel : selections) {
                sel.recordRemoved((SimpleFragment) fragment);
            }
        }

        RowId rowId = fragment.row;
        switch (fragment.getState()) {
        case ABSENT:
        case INVALIDATED_DELETED:
            pristine.remove(rowId);
            break;
        case CREATED:
            modified.remove(rowId);
            break;
        case PRISTINE:
        case INVALIDATED_MODIFIED:
            pristine.remove(rowId);
            modified.put(rowId, fragment);
            break;
        case MODIFIED:
            // already in modified
            break;
        case DETACHED:
        case DELETED:
            break;
        }
        fragment.setDeleted();
    }

    /**
     * Recomputes isLatest / isLatestMajor on all versions.
     */
    public void recomputeVersionSeries(Serializable versionSeriesId)
            throws StorageException {
        List<SimpleFragment> versFrags = seriesVersions.getSelectionFragments(
                versionSeriesId, null);
        Collections.sort(versFrags, VER_CREATED_COMPARATOR);
        Collections.reverse(versFrags);
        boolean isLatest = true;
        boolean isLatestMajor = true;
        for (SimpleFragment vsf : versFrags) {

            // isLatestVersion
            vsf.put(model.VERSION_IS_LATEST_KEY, Boolean.valueOf(isLatest));
            isLatest = false;

            // isLatestMajorVersion
            SimpleFragment vh = getHier(vsf.getId(), true);
            boolean isMajor = Long.valueOf(0).equals(
                    vh.get(model.MAIN_MINOR_VERSION_KEY));
            vsf.put(model.VERSION_IS_LATEST_MAJOR_KEY,
                    Boolean.valueOf(isMajor && isLatestMajor));
            if (isMajor) {
                isLatestMajor = false;
            }
        }
    }

    /**
     * Gets the version ids for a version series, ordered by creation time.
     */
    public List<Serializable> getVersionIds(Serializable versionSeriesId)
            throws StorageException {
        List<SimpleFragment> fragments = seriesVersions.getSelectionFragments(
                versionSeriesId, null);
        Collections.sort(fragments, VER_CREATED_COMPARATOR);
        return fragmentsIds(fragments);
    }

    public List<Serializable> getSeriesProxyIds(Serializable versionSeriesId)
            throws StorageException {
        List<SimpleFragment> fragments = seriesProxies.getSelectionFragments(
                versionSeriesId, null);
        return fragmentsIds(fragments);
    }

    public List<Serializable> getTargetProxyIds(Serializable targetId)
            throws StorageException {
        List<SimpleFragment> fragments = targetProxies.getSelectionFragments(
                targetId, null);
        return fragmentsIds(fragments);
    }

    private List<Serializable> fragmentsIds(List<? extends Fragment> fragments) {
        List<Serializable> ids = new ArrayList<Serializable>(fragments.size());
        for (Fragment fragment : fragments) {
            ids.add(fragment.getId());
        }
        return ids;
    }

    /*
     * ----- Hierarchy -----
     */

    /**
     * Finds the id of the enclosing non-complex-property node.
     *
     * @param id the id
     * @return the id of the containing document, or {@code null} if there is no
     *         parent or the parent has been deleted.
     */
    public Serializable getContainingDocument(Serializable id)
            throws StorageException {
        Serializable pid = id;
        while (true) {
            if (pid == null) {
                // no parent
                return null;
            }
            SimpleFragment p = getHier(pid, false);
            if (p == null) {
                // can happen if the fragment has been deleted
                return null;
            }
            if (!complexProp(p)) {
                return pid;
            }
            pid = p.get(model.HIER_PARENT_KEY);
        }
    }

    // also called by Selection
    protected SimpleFragment getHier(Serializable id, boolean allowAbsent)
            throws StorageException {
        RowId rowId = new RowId(model.HIER_TABLE_NAME, id);
        return (SimpleFragment) get(rowId, allowAbsent);
    }

    private boolean isOrderable(Serializable parentId, boolean complexProp)
            throws StorageException {
        if (complexProp) {
            return true;
        }
        SimpleFragment parent = getHier(parentId, true);
        String typeName = parent.getString(model.MAIN_PRIMARY_TYPE_KEY);
        return model.getDocumentTypeFacets(typeName).contains(
                FacetNames.ORDERABLE);
    }

    /** Recursively checks if any of a fragment's parents has been deleted. */
    // needed because we don't recursively clear caches when doing a delete
    public boolean isDeleted(Serializable id) throws StorageException {
        while (id != null) {
            SimpleFragment fragment = getHier(id, false);
            State state;
            if (fragment == null
                    || (state = fragment.getState()) == State.ABSENT
                    || state == State.DELETED
                    || state == State.INVALIDATED_DELETED) {
                return true;
            }
            id = fragment.get(model.HIER_PARENT_KEY);
        }
        return false;
    }

    /**
     * Gets the next pos value for a new child in a folder.
     *
     * @param nodeId the folder node id
     * @param complexProp whether to deal with complex properties or regular
     *            children
     * @return the next pos, or {@code null} if not orderable
     */
    public Long getNextPos(Serializable nodeId, boolean complexProp)
            throws StorageException {
        if (!isOrderable(nodeId, complexProp)) {
            return null;
        }
        long max = -1;
        for (SimpleFragment fragment : getChildren(nodeId, null, complexProp)) {
            Long pos = (Long) fragment.get(model.HIER_CHILD_POS_KEY);
            if (pos != null && pos.longValue() > max) {
                max = pos.longValue();
            }
        }
        return Long.valueOf(max + 1);
    }

    /**
     * Order a child before another.
     *
     * @param parentId the parent id
     * @param sourceId the node id to move
     * @param destId the node id before which to place the source node, if
     *            {@code null} then move the source to the end
     */
    public void orderBefore(Serializable parentId, Serializable sourceId,
            Serializable destId) throws StorageException {
        boolean complexProp = false;
        if (!isOrderable(parentId, complexProp)) {
            // TODO throw exception?
            return;
        }
        if (sourceId.equals(destId)) {
            return;
        }
        // This is optimized by assuming the number of children is small enough
        // to be manageable in-memory.
        // fetch children and relevant nodes
        List<SimpleFragment> fragments = getChildren(parentId, null,
                complexProp);
        // renumber fragments
        int i = 0;
        SimpleFragment source = null; // source if seen
        Long destPos = null;
        for (SimpleFragment fragment : fragments) {
            Serializable id = fragment.getId();
            if (id.equals(destId)) {
                destPos = Long.valueOf(i);
                i++;
                if (source != null) {
                    source.put(model.HIER_CHILD_POS_KEY, destPos);
                }
            }
            Long setPos;
            if (id.equals(sourceId)) {
                i--;
                source = fragment;
                setPos = destPos;
            } else {
                setPos = Long.valueOf(i);
            }
            if (setPos != null) {
                if (!setPos.equals(fragment.get(model.HIER_CHILD_POS_KEY))) {
                    fragment.put(model.HIER_CHILD_POS_KEY, setPos);
                }
            }
            i++;
        }
        if (destId == null) {
            Long setPos = Long.valueOf(i);
            if (!setPos.equals(source.get(model.HIER_CHILD_POS_KEY))) {
                source.put(model.HIER_CHILD_POS_KEY, setPos);
            }
        }
    }

    public SimpleFragment getChildHierByName(Serializable parentId,
            String name, boolean complexProp) throws StorageException {
        return getHierSelectionContext(complexProp).getSelectionFragment(
                parentId, name);
    }

    public List<SimpleFragment> getChildren(Serializable parentId, String name,
            boolean complexProp) throws StorageException {
        List<SimpleFragment> fragments = getHierSelectionContext(complexProp).getSelectionFragments(
                parentId, name);
        if (isOrderable(parentId, complexProp)) {
            // sort children in order
            Collections.sort(fragments, POS_COMPARATOR);
        }
        return fragments;
    }

    /** Checks that we don't move/copy under ourselves. */
    protected void checkNotUnder(Serializable parentId, Serializable id,
            String op) throws StorageException {
        Serializable pid = parentId;
        do {
            if (pid.equals(id)) {
                throw new StorageException("Cannot " + op
                        + " a node under itself: " + parentId + " is under "
                        + id);
            }
            SimpleFragment p = getHier(pid, false);
            if (p == null) {
                // cannot happen
                throw new StorageException("No parent: " + pid);
            }
            pid = p.get(model.HIER_PARENT_KEY);
        } while (pid != null);
    }

    /** Checks that a name is free. Cannot check concurrent sessions though. */
    protected void checkFreeName(Serializable parentId, String name,
            boolean complexProp) throws StorageException {
        Fragment fragment = getChildHierByName(parentId, name, complexProp);
        if (fragment != null) {
            throw new StorageException("Destination name already exists: "
                    + name);
        }
    }

    /**
     * Move a child to a new parent with a new name.
     *
     * @param source the source
     * @param parentId the destination parent id
     * @param name the new name
     * @throws StorageException
     */
    public void move(Node source, Serializable parentId, String name)
            throws StorageException {
        // a save() has already been done by the caller when doing
        // an actual move (different parents)
        Serializable id = source.getId();
        SimpleFragment hierFragment = source.getHierFragment();
        Serializable oldParentId = hierFragment.get(model.HIER_PARENT_KEY);
        String oldName = hierFragment.getString(model.HIER_CHILD_NAME_KEY);
        if (!oldParentId.equals(parentId)) {
            checkNotUnder(parentId, id, "move");
        } else if (oldName.equals(name)) {
            // null move
            return;
        }
        boolean complexProp = complexProp(hierFragment);
        checkFreeName(parentId, name, complexProp);
        /*
         * Do the move.
         */
        if (!oldName.equals(name)) {
            hierFragment.put(model.HIER_CHILD_NAME_KEY, name);
        }
        // cache management
        getHierSelectionContext(complexProp).recordRemoved(hierFragment);
        hierFragment.put(model.HIER_PARENT_KEY, parentId);
        getHierSelectionContext(complexProp).recordExisting(hierFragment, true);
    }

    /**
     * Copy a child to a new parent with a new name.
     *
     * @param source the source of the copy
     * @param parentId the destination parent id
     * @param name the new name
     * @return the id of the copy
     */
    public Serializable copy(Node source, Serializable parentId, String name)
            throws StorageException {
        Serializable id = source.getId();
        SimpleFragment hierFragment = source.getHierFragment();
        Serializable oldParentId = hierFragment.get(model.HIER_PARENT_KEY);
        if (!oldParentId.equals(parentId)) {
            checkNotUnder(parentId, id, "copy");
        }
        checkFreeName(parentId, name, complexProp(hierFragment));
        // do the copy
        CopyResult copyResult = mapper.copy(new IdWithTypes(source),
                parentId, name, null);
        Serializable newId = copyResult.copyId;
        // read new child in this session (updates children Selection)
        getHier(newId, false);
        // invalidate child in other sessions' children Selection
        markInvalidated(copyResult.invalidations);
        // read new proxies in this session (updates Selections)
        List<RowId> rowIds = new ArrayList<RowId>();
        for (Serializable proxyId : copyResult.proxyIds) {
            rowIds.add(new RowId(model.PROXY_TABLE_NAME, proxyId));
        }
        // multi-fetch will register the new fragments with the Selections
        List<Fragment> fragments = getMulti(rowIds, true);
        // invalidate Selections in other sessions
        for (Fragment fragment : fragments) {
            seriesProxies.recordExisting((SimpleFragment) fragment, true);
            targetProxies.recordExisting((SimpleFragment) fragment, true);
        }
        return newId;
    }

    /**
     * Checks in a node (creates a version).
     *
     * @param node the node to check in
     * @param label the version label
     * @param checkinComment the version description
     * @return the created version id
     */
    public Serializable checkIn(Node node, String label, String checkinComment)
            throws StorageException {
        Boolean checkedIn = (Boolean) node.hierFragment.get(model.MAIN_CHECKED_IN_KEY);
        if (Boolean.TRUE.equals(checkedIn)) {
            throw new StorageException("Already checked in");
        }
        if (label == null) {
            // use version major + minor as label
            try {
                Serializable major = node.getSimpleProperty(
                        model.MAIN_MAJOR_VERSION_PROP).getValue();
                Serializable minor = node.getSimpleProperty(
                        model.MAIN_MINOR_VERSION_PROP).getValue();
                if (major == null || minor == null) {
                    label = "";
                } else {
                    label = major + "." + minor;
                }
            } catch (StorageException e) {
                log.error("Cannot get version", e);
                label = "";
            }
        }

        /*
         * Do the copy without non-complex children, with null parent.
         */
        Serializable id = node.getId();
        CopyResult res = mapper.copy(new IdWithTypes(node), null,
                null, null);
        Serializable newId = res.copyId;
        markInvalidated(res.invalidations);
        // add version as a new child of its parent
        SimpleFragment verHier = getHier(newId, false);
        verHier.put(model.MAIN_IS_VERSION_KEY, Boolean.TRUE);
        boolean isMajor = Long.valueOf(0).equals(
                verHier.get(model.MAIN_MINOR_VERSION_KEY));

        // create a "version" row for our new version
        Row row = new Row(model.VERSION_TABLE_NAME, newId);
        row.putNew(model.VERSION_VERSIONABLE_KEY, id);
        row.putNew(model.VERSION_CREATED_KEY, new GregorianCalendar()); // now
        row.putNew(model.VERSION_LABEL_KEY, label);
        row.putNew(model.VERSION_DESCRIPTION_KEY, checkinComment);
        row.putNew(model.VERSION_IS_LATEST_KEY, Boolean.TRUE);
        row.putNew(model.VERSION_IS_LATEST_MAJOR_KEY, Boolean.valueOf(isMajor));
        createVersionFragment(row);

        // update the original node to reflect that it's checked in
        node.hierFragment.put(model.MAIN_CHECKED_IN_KEY, Boolean.TRUE);
        node.hierFragment.put(model.MAIN_BASE_VERSION_KEY, newId);

        recomputeVersionSeries(id);

        return newId;
    }

    /**
     * Checks out a node.
     *
     * @param node the node to check out
     */
    public void checkOut(Node node) throws StorageException {
        Boolean checkedIn = (Boolean) node.hierFragment.get(model.MAIN_CHECKED_IN_KEY);
        if (!Boolean.TRUE.equals(checkedIn)) {
            throw new StorageException("Already checked out");
        }
        // update the node to reflect that it's checked out
        node.hierFragment.put(model.MAIN_CHECKED_IN_KEY, Boolean.FALSE);
    }

    /**
     * Restores a node to a given version.
     * <p>
     * The restored node is checked in.
     *
     * @param node the node
     * @param version the version to restore on this node
     */
    public void restoreVersion(Node node, Node version) throws StorageException {
        Serializable versionableId = node.getId();
        Serializable versionId = version.getId();

        // clear complex properties
        List<SimpleFragment> children = getChildren(versionableId, null, true);
        // copy to avoid concurrent modifications
        for (Fragment child : children.toArray(new Fragment[children.size()])) {
            removeFragment(child); // will cascade deletes
        }
        session.flush(); // flush deletes

        // copy the version values
        Row overwriteRow = new Row(model.HIER_TABLE_NAME, versionableId);
        SimpleFragment versionHier = version.getHierFragment();
        for (String key : model.getFragmentKeysType(model.HIER_TABLE_NAME).keySet()) {
            // keys we don't copy from version when restoring
            if (key.equals(model.HIER_PARENT_KEY)
                    || key.equals(model.HIER_CHILD_NAME_KEY)
                    || key.equals(model.HIER_CHILD_POS_KEY)
                    || key.equals(model.HIER_CHILD_ISPROPERTY_KEY)
                    || key.equals(model.MAIN_PRIMARY_TYPE_KEY)
                    || key.equals(model.MAIN_CHECKED_IN_KEY)
                    || key.equals(model.MAIN_BASE_VERSION_KEY)
                    || key.equals(model.MAIN_IS_VERSION_KEY)) {
                continue;
            }
            overwriteRow.putNew(key, versionHier.get(key));
        }
        overwriteRow.putNew(model.MAIN_CHECKED_IN_KEY, Boolean.TRUE);
        overwriteRow.putNew(model.MAIN_BASE_VERSION_KEY, versionId);
        overwriteRow.putNew(model.MAIN_IS_VERSION_KEY, null);
        CopyResult res = mapper.copy(new IdWithTypes(version),
                node.getParentId(), null, overwriteRow);
        markInvalidated(res.invalidations);
    }

}
