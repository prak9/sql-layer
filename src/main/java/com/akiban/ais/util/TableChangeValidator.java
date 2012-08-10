/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.ais.util;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.IndexColumn;
import com.akiban.ais.model.Join;
import com.akiban.ais.model.JoinColumn;
import com.akiban.ais.model.UserTable;
import com.akiban.server.types3.Types3Switch;
import com.akiban.util.ArgumentValidation;
import com.akiban.util.MultipleCauseException;
import com.google.common.base.Objects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.akiban.ais.util.TableChangeValidatorException.*;

public class TableChangeValidator {
    public static enum ChangeLevel {
        NONE,
        METADATA,
        METADATA_NULL,
        INDEX,
        TABLE,
        GROUP
    }

    private final UserTable oldTable;
    private final UserTable newTable;
    private final List<TableChange> columnChanges;
    private final List<TableChange> indexChanges;
    private final List<RuntimeException> errors;
    private ChangeLevel finalChangeLevel;
    private boolean parentChange;
    private boolean childrenChange;
    private boolean didCompare;

    public TableChangeValidator(UserTable oldTable, UserTable newTable,
                                List<TableChange> columnChanges, List<TableChange> indexChanges) {
        ArgumentValidation.notNull("oldTable", oldTable);
        ArgumentValidation.notNull("newTable", newTable);
        this.oldTable = oldTable;
        this.newTable = newTable;
        this.columnChanges = (columnChanges == null) ? Collections.<TableChange>emptyList() : columnChanges;
        this.indexChanges = (indexChanges == null) ? Collections.<TableChange>emptyList() : indexChanges;
        this.errors = new ArrayList<RuntimeException>();
        this.finalChangeLevel = ChangeLevel.NONE;
    }

    public ChangeLevel getFinalChangeLevel() {
        return finalChangeLevel;
    }

    public boolean didParentChange() {
        return parentChange;
    }

    public boolean didChildrenChange() {
        return childrenChange;
    }

    public void compare() {
        if(!didCompare) {
            compareTable();
            compareColumns();
            compareIndexes();
            compareGrouping();
            updateFinalChangeLevel(ChangeLevel.NONE);
            didCompare = true;
        }
    }

    public void compareAndThrowIfNecessary() {
        compare();
        switch(errors.size()) {
            case 0:
                return;
            case 1:
                throw errors.get(0);
            default:
                MultipleCauseException mce = new MultipleCauseException();
                for(Exception e : errors) {
                    mce.addCause(e);
                }
                throw mce;
        }
    }

    private void updateFinalChangeLevel(ChangeLevel level) {
        if(errors.isEmpty()) {
            if(level.ordinal() > finalChangeLevel.ordinal()) {
                finalChangeLevel = level;
            }
        } else {
            finalChangeLevel = null;
        }
    }

    private void compareTable() {
        if(!oldTable.getName().equals(newTable.getName())) {
            updateFinalChangeLevel(ChangeLevel.METADATA);
        }
    }

    private void compareColumns() {
        Map<String,Column> oldColumns = new HashMap<String,Column>();
        Map<String,Column> newColumns = new HashMap<String,Column>();
        for(Column column : oldTable.getColumnsIncludingInternal()) {
            oldColumns.put(column.getName(), column);
        }
        for(Column column : newTable.getColumnsIncludingInternal()) {
            newColumns.put(column.getName(), column);
        }
        checkChanges(ChangeLevel.TABLE, columnChanges, oldColumns, newColumns);
    }

    private void compareIndexes() {
        Map<String,Index> oldIndexes = new HashMap<String,Index>();
        Map<String,Index> newIndexes = new HashMap<String,Index>();
        for(Index index : oldTable.getIndexesIncludingInternal()) {
            oldIndexes.put(index.getIndexName().getName(), index);
        }
        for(Index index : newTable.getIndexesIncludingInternal()) {
            newIndexes.put(index.getIndexName().getName(), index);
        }
        checkChanges(ChangeLevel.INDEX, indexChanges, oldIndexes, newIndexes);
    }

    private <T> void checkChanges(ChangeLevel level, List<TableChange> changeList, Map<String,T> oldMap, Map<String,T> newMap) {
        final boolean isIndex = (level == ChangeLevel.INDEX);
        Set<String> oldExcludes = new HashSet<String>();
        Set<String> newExcludes = new HashSet<String>();

        // Check declared changes
        for(TableChange change : changeList) {
            String oldName = change.getOldName();
            String newName = change.getNewName();
            switch(change.getChangeType()) {
                case ADD: {
                    if(newMap.get(newName) == null) {
                        addNotPresent(isIndex, change);
                    } else {
                        updateFinalChangeLevel(level);
                        newExcludes.add(newName);
                    }
                }
                break;

                case DROP: {
                    if(oldMap.get(oldName) == null) {
                        dropNotPresent(isIndex, change);
                    } else {
                        updateFinalChangeLevel(level);
                        oldExcludes.add(oldName);
                    }
                }
                break;

                case MODIFY: {
                    T oldVal = oldMap.get(oldName);
                    T newVal = newMap.get(newName);
                    if((oldVal == null) || (newVal == null)) {
                        modifyNotPresent(isIndex, change);
                    } else {
                        ChangeLevel curChange = compare(oldVal, newVal);
                        if(curChange == ChangeLevel.NONE) {
                            modifyNotChanged(isIndex, change);
                        } else {
                            updateFinalChangeLevel(curChange);
                            oldExcludes.add(oldName);
                            newExcludes.add(newName);
                        }
                    }
                }
                break;
            }
        }

        // Check remaining elements in old table
        for(Map.Entry<String,T> entry : oldMap.entrySet()) {
            String name = entry.getKey();
            if(!oldExcludes.contains(name)) {
                T newVal = newMap.get(name);
                if(newVal == null) {
                    unchangedNotPresent(isIndex, name);
                } else {
                    ChangeLevel change = compare(entry.getValue(), newVal);
                    if(change != ChangeLevel.NONE) {
                        undeclaredChange(isIndex, name);
                    }
                    newExcludes.add(name);
                }
            }
        }

        // Check remaining elements in new table (should be none)
        for(String name : newMap.keySet()) {
            if(!newExcludes.contains(name)) {
                undeclaredChange(isIndex, name);
            }
        }
    }

    private void compareGrouping() {
        // Note: PK (grouping) changes checked in compareColumns()
        parentChange = joinChanged(oldTable.getParentJoin(), newTable.getParentJoin());

        // PRIMARY was added or removed or column in PRIMARY was modified
        // No need to inspect columnChanges as any affecting should is required (and checked) to be in indexChanges
        childrenChange = false;
        for(TableChange change : indexChanges) {
            switch(change.getChangeType()) {
                case DROP:
                case MODIFY:
                    if(Index.PRIMARY_KEY_CONSTRAINT.equals(change.getOldName()) ||
                       Index.PRIMARY_KEY_CONSTRAINT.equals(change.getNewName())) {
                        childrenChange = true;
                    }
                break;
            }
        }

        if(parentChange || childrenChange) {
            updateFinalChangeLevel(ChangeLevel.GROUP);
        }
    }

    private String findNewColumnName(String oldName) {
        for(TableChange change : columnChanges) {
            if(oldName.equals(change.getOldName())) {
                switch(change.getChangeType()) {
                    case DROP:
                        return null;
                    case MODIFY:
                        return change.getNewName();
                }
            }
        }
        return oldName;
    }

    private <T> ChangeLevel compare(T oldVal, T newVal) {
        if(oldVal instanceof Column) {
            return compare((Column)oldVal, (Column)newVal);
        }
        if(oldVal instanceof Index) {
            return compare((Index)oldVal, (Index)newVal);
        }
        throw new IllegalStateException("Cannot compare: " + oldVal + " and " + newVal);
    }

    private static ChangeLevel compare(Column oldCol, Column newCol) {
        if(Types3Switch.ON) {
            if(!oldCol.tInstance().equalsExcludingNullable(newCol.tInstance())) {
                return ChangeLevel.TABLE;
            }
        } else {
            if(!oldCol.getType().equals(newCol.getType()) ||
               !Objects.equal(oldCol.getTypeParameter1(), newCol.getTypeParameter1()) ||
               !Objects.equal(oldCol.getTypeParameter2(), newCol.getTypeParameter2()) ||
               (oldCol.getType().usesCollator() && !Objects.equal(oldCol.getCharsetAndCollation(), newCol.getCharsetAndCollation()))) {
                return ChangeLevel.TABLE;
            }
        }
        if(!oldCol.getNullable().equals(newCol.getNullable())) {
            return ChangeLevel.METADATA_NULL;
        }
        if(!oldCol.getName().equals(newCol.getName()) ||
           !Objects.equal(oldCol.getIdentityGenerator(), newCol.getIdentityGenerator())) {
          return ChangeLevel.METADATA;
        }

        // TODO: Check defaults

        return ChangeLevel.NONE;
    }

    private ChangeLevel compare(Index oldIndex, Index newIndex) {
        if(oldIndex.getKeyColumns().size() != newIndex.getKeyColumns().size()) {
            return ChangeLevel.INDEX;
        }

        Iterator<IndexColumn> oldIt = oldIndex.getKeyColumns().iterator();
        Iterator<IndexColumn> newIt = newIndex.getKeyColumns().iterator();
        while(oldIt.hasNext()) {
            IndexColumn oldICol = oldIt.next();
            IndexColumn newICol = newIt.next();
            String newColName = findNewColumnName(oldICol.getColumn().getName());
            // Column the same?
            if((newColName == null) || !newICol.getColumn().getName().equals(newColName)) {
                return ChangeLevel.INDEX;
            }
            // IndexColumn properties
            if(!Objects.equal(oldICol.getIndexedLength(), newICol.getIndexedLength()) ||
               !Objects.equal(oldICol.isAscending(), newICol.isAscending())) {
                return ChangeLevel.INDEX;
            }
            // Column being indexed
            if(compare(oldICol.getColumn(), newICol.getColumn()) == ChangeLevel.TABLE) {
                return ChangeLevel.INDEX;
            }
        }

        if(!oldIndex.getIndexName().getName().equals(newIndex.getIndexName().getName())) {
            return ChangeLevel.METADATA;
        }
        return ChangeLevel.NONE;
    }

    private static boolean joinChanged(Join oldJoin, Join newJoin) {
        if(oldJoin == null && newJoin == null) {
            return false;
        }
        if(oldJoin != null && newJoin == null) {
            return true;
        }
        if(oldJoin == null /*&& newJoin != null*/) {
            return true;
        }

        UserTable oldParent = oldJoin.getParent();
        UserTable newParent = newJoin.getParent();
        if(!oldParent.getName().equals(newParent.getName()) ||
           (oldJoin.getJoinColumns().size() != newJoin.getJoinColumns().size())) {
            return true;
        }

        Iterator<JoinColumn> oldIt = oldJoin.getJoinColumns().iterator();
        Iterator<JoinColumn> newIt = newJoin.getJoinColumns().iterator();
        while(oldIt.hasNext()) {
            JoinColumn oldCol = oldIt.next();
            JoinColumn newCol = newIt.next();
            if(!oldCol.getParent().getName().equals(newCol.getParent().getName()) ||
               !oldCol.getChild().getName().equals(newCol.getChild().getName())) {
                return true;
            }
        }
        return false;
    }


    private void addNotPresent(boolean isIndex, TableChange change) {
        String detail = change.toString();
        errors.add(isIndex ? new AddIndexNotPresentException(detail) : new AddColumnNotPresentException(detail));
    }

    private void dropNotPresent(boolean isIndex, TableChange change) {
        String detail = change.toString();
        errors.add(isIndex ? new DropIndexNotPresentException(detail) : new DropColumnNotPresentException(detail));
    }

    private void modifyNotChanged(boolean isIndex, TableChange change) {
        String detail = change.toString();
        errors.add(isIndex ? new ModifyIndexNotChangedException(detail) : new ModifyColumnNotChangedException(detail));
    }

    private void modifyNotPresent(boolean isIndex, TableChange change) {
        String detail = change.toString();
        errors.add(isIndex ? new ModifyIndexNotPresentException(detail) : new ModifyColumnNotPresentException(detail));
    }

    private void unchangedNotPresent(boolean isIndex, String detail) {
        errors.add(isIndex ? new UnchangedIndexNotPresentException(detail) : new UnchangedColumnNotPresentException(detail));
    }

    private void undeclaredChange(boolean isIndex, String detail) {
        errors.add(isIndex ? new UndeclaredIndexChangeException(detail) : new UndeclaredColumnChangeException(detail));
    }
}