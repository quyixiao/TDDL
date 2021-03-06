package com.taobao.tddl.executor.cursor.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.TreeMap;

import org.apache.commons.lang.StringUtils;

import com.taobao.tddl.common.utils.GeneralUtil;
import com.taobao.tddl.common.utils.TStringUtil;
import com.taobao.tddl.executor.common.IRowsValueScaner;
import com.taobao.tddl.executor.common.RowsValueScanerImp;
import com.taobao.tddl.executor.cursor.ICursorMeta;
import com.taobao.tddl.executor.exception.ExecutorException;
import com.taobao.tddl.executor.utils.ExecUtils;
import com.taobao.tddl.optimizer.config.table.ColumnMessage;
import com.taobao.tddl.optimizer.config.table.ColumnMeta;
import com.taobao.tddl.optimizer.core.expression.ISelectable;

public class CursorMetaImp implements ICursorMeta {

    private CursorMetaImp(String name, List<ColumnMessage> columns, Integer indexRange){
        super();
        // this.name = name;
        this.columns = new ArrayList<ColumnMeta>();
        this.indexRange = indexRange;
        int index = 0;

        for (ColumnMessage cm : columns) {
            String colName = cm.getName();
            String tabName = name;
            addAColumn(tabName, colName, cm.getAlias(), index);

            ColumnMeta columnMeta = new ColumnMeta(name,
                cm.getName(),
                cm.getDataType(),
                cm.getAlias(),
                cm.getNullable());
            this.columns.add(columnMeta);

            this.indexToColumnMeta.put(index, columnMeta);
            index++;
        }

        if (indexMap == null) {
            throw new ExecutorException("impossible, indexMap is null");
        }
    }

    private CursorMetaImp(String name, List<ColumnMessage> columns, List<Integer> indexes, Integer indexRange){
        // this.name = name;
        this.columns = new ArrayList<ColumnMeta>();
        Iterator<Integer> iteratorIndex = indexes.iterator();

        this.columns = new ArrayList(columns.size());

        for (ColumnMessage cm : columns) {
            if (!iteratorIndex.hasNext()) {
                throw new IllegalArgumentException("iterator and columns not match");
            }
            String colName = cm.getName();
            String tabName = name;

            Integer index = iteratorIndex.next();
            addAColumn(tabName, colName, cm.getAlias(), index);

            ColumnMeta columnMeta = new ColumnMeta(name,
                cm.getName(),
                cm.getDataType(),
                cm.getAlias(),
                cm.getNullable());
            this.columns.add(columnMeta);

            this.indexToColumnMeta.put(index, columnMeta);
        }
        this.indexRange = indexRange;
        if (indexMap == null) {
            throw new ExecutorException("impossible, indexMap is null");
        }

    }

    private CursorMetaImp(List<ColumnMeta> columns, List<Integer> indexes, Integer indexRange){
        Iterator<Integer> iteratorIndex = indexes.iterator();

        this.columns = new ArrayList(columns.size());

        for (ColumnMeta cm : columns) {
            if (!iteratorIndex.hasNext()) {
                throw new IllegalArgumentException("iterator and columns not match");
            }
            String colName = cm.getName();
            String tabName = cm.getTableName();

            Integer index = iteratorIndex.next();
            addAColumn(tabName, colName, cm.getAlias(), index);

            ColumnMeta columnMeta = new ColumnMeta(cm.getTableName(),
                cm.getName(),
                cm.getDataType(),
                cm.getAlias(),
                cm.isNullable());
            this.columns.add(columnMeta);

            this.indexToColumnMeta.put(index, cm);
        }
        this.indexRange = indexRange;
        if (indexMap == null) {
            throw new ExecutorException("impossible, indexMap is null");
        }

    }

    private CursorMetaImp(List<ColumnMeta> columns, Integer indexRange){
        super();
        this.columns = new ArrayList<ColumnMeta>(columns);
        int index = 0;
        for (ColumnMeta cm : columns) {
            String colName = cm.getName();
            String tabName = cm.getTableName();
            addAColumn(tabName, colName, cm.getAlias(), index);

            this.indexToColumnMeta.put(index, cm);

            index++;

        }
        this.indexRange = indexRange;
        if (indexMap == null) {
            throw new ExecutorException("impossible, indexMap is null");
        }

    }

    private CursorMetaImp(List<ColumnMeta> columns){
        super();
        this.columns = new ArrayList<ColumnMeta>(columns);
        int index = 0;
        for (ColumnMeta cm : columns) {
            String colName = cm.getName();
            String tabName = cm.getTableName();
            addAColumn(tabName, colName, cm.getAlias(), index);

            this.indexToColumnMeta.put(index, cm);

            index++;

        }
        if (indexMap == null) {
            indexMap = new HashMap<String, CursorMetaImp.ColumnHolder>();
        }

        this.indexRange = index;
    }

    /**
     * ????????????+index ??????????????????????????????????????????????????? ??????????????????????????????????????????hash,?????????????????????????????????????????????????????????????????????
     * ???????????????map?????????
     * 
     * @author whisper
     */
    public static class ColumnHolder {

        public ColumnHolder(ColumnHolder next, String tablename, Integer index){
            super();
            this.next = next;
            this.tablename = tablename;
            this.index = index;
        }

        /**
         * ???????????????????????????????????????????????? ??????????????????????????????????????????
         */
        ColumnHolder next      = null;
        /**
         * ??????
         */
        String       tablename = null;
        /**
         * indexName
         */
        Integer      index     = null;

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("ColumnHolder [\t");
            if (next != null) {
                builder.append("next:");
                builder.append(next);
                builder.append(", \t");
            }
            if (tablename != null) {
                builder.append("tablename:");
                builder.append(tablename);
                builder.append(",\t");
            }
            if (index != null) {
                builder.append("index:");
                builder.append(index);
            }
            builder.append("]\n");
            return builder.toString();
        }

    }

    private String                                                   name;

    private List<ColumnMeta>                                         columns;

    private Map<String/* ??????????????????????????????????????????????????????????????????????????? */, ColumnHolder> indexMap          = new TreeMap<String, CursorMetaImp.ColumnHolder>(String.CASE_INSENSITIVE_ORDER);

    private Integer                                                  indexRange;

    private boolean                                                  isSureLogicalIndexEqualActualIndex;

    private Map<Integer, ColumnMeta>                                 indexToColumnMeta = new HashMap();

    @Override
    public Integer getIndexRange() {
        return indexRange;
    }

    // @Override
    // public String getName() {
    // return name;
    // }

    @Override
    public List<ColumnMeta> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    @Override
    public Integer getIndex(String tableName, String columnName, String columnAlias) {

        Integer index = null;
        if (columnAlias != null) {
            index = getIndex(tableName, columnAlias);
        }

        if (index == null) {
            index = getIndex(tableName, columnName);

        }

        return index;

    }

    private Integer getIndex(String tableName, String columnName) {
        tableName = ExecUtils.getLogicTableName(tableName);
        ColumnHolder ch = indexMap.get(columnName);
        if (ch == null) {
            return null;
        }
        // ?????????P
        Integer index = null;
        if (tableName == null /* || ch.next == null */) {// hook tableName ==
            // null.????????????,????????????????????????????????????????????????????????????????????????????????????????????????
            return ch.index;
        }
        index = findTableName(tableName, ch);
        if (index != null) {
            return index;
        }
        ColumnHolder nextCh = ch;
        // ?????????ColumnHolder
        while ((nextCh = nextCh.next) != null) {
            index = findTableName(tableName, nextCh);
            if (index != null) {
                return index;
            }
        }

        return ch.index;
    }

    private static Integer findTableName(String tableName, ColumnHolder ch) {
        if (TStringUtil.equals(tableName, ch.tablename)) {
            return ch.index;
        }
        return null;
    }

    public Map<String, ColumnHolder> getIndexMap() {
        return Collections.unmodifiableMap(indexMap);
    }

    public static CursorMetaImp buildNew(List<ColumnMeta> columns) {
        return new CursorMetaImp(columns);
    }

    public static CursorMetaImp buildNew(String name, List<ColumnMessage> columns, Integer indexRange) {
        return new CursorMetaImp(name, columns, indexRange);
    }

    public static CursorMetaImp buildNew(List<ColumnMeta> columns, Integer indexRange) {
        return new CursorMetaImp(columns, indexRange);
    }

    public static CursorMetaImp buildNew(String name, List<ColumnMessage> columns, List<Integer> indexes,
                                         Integer indexRange) {
        return new CursorMetaImp(name, columns, indexes, indexRange);
    }

    public static CursorMetaImp buildNew(List<ColumnMeta> columns, List<Integer> indexes, Integer indexRange) {
        return new CursorMetaImp(columns, indexes, indexRange);
    }

    protected void addAColumn(String tableName, String colName, String colAlias, Integer index) {

        tableName = ExecUtils.getLogicTableName(tableName);
        ColumnHolder ch = indexMap.get(colName);
        if (ch == null) {
            ch = new ColumnHolder(null, tableName, index);
            indexMap.put(colName, ch);

            return;
        }

        boolean success = findTableAndReplaceIndexNumber(tableName, index, ch);
        if (success) {
            return;
        }
        ColumnHolder nextCh;
        // ?????????ColumnHolder
        while ((nextCh = ch.next) != null) {
            success = findTableAndReplaceIndexNumber(tableName, index, nextCh);
            if (success) {
                return;
            }

            ch = ch.next;
        }

    }

    // protected void addAColumn(Map<String, ColumnHolder> columnHolderMap,
    // String tableName, String colName, Integer index) {
    //
    // }

    /**
     * ????????????????????????????????????????????????index ???????????????????????????????????????????????????????????????ColumnHolder ??????????????????????????? ???????????????
     * 
     * @param tableName
     * @param index
     * @param ch
     * @return
     */
    private static boolean findTableAndReplaceIndexNumber(String tableName, Integer index, ColumnHolder ch) {
        boolean success = false;
        if (StringUtils.equals(tableName, ch.tablename)) {
            ch.index = index;
            success = true;
        } else if (ch.next == null) {
            ch.next = new ColumnHolder(null, tableName, index);
            success = true;
        }
        return success;
    }

    @Override
    public String toStringWithInden(int inden) {
        StringBuilder sb = new StringBuilder();
        String tabTittle = GeneralUtil.getTab(inden);
        sb.append(tabTittle).append("[");
        sb.append("cursor meta name : ").append(name).append("\t");
        List<ColumnMeta> metas = columns;
        if (metas != null) {
            for (ColumnMeta cm : metas) {
                sb.append(cm.toStringWithInden(0)).append(":");
                sb.append(getIndex(cm.getTableName(), cm.getName()));
                sb.append(" ");
            }
        }
        return sb.toString();

    }

    private static class IndexMetaIterator implements Iterator<ColMetaAndIndex> {

        Iterator<Entry<String, ColumnHolder>> entryIterator = null;
        /**
         * ??????iterator ???????????????????????????????????????????????????
         */
        ColMetaAndIndex                       current       = null;

        public IndexMetaIterator(Map<String/* ????????? */, ColumnHolder> indexMap){
            entryIterator = indexMap.entrySet().iterator();
        }

        @Override
        public boolean hasNext() {
            if (current == null) {
                return entryIterator.hasNext();
            } else {
                boolean hasNext = current.getColumnHolder().next != null;
                if (!hasNext) {
                    current = null;
                    hasNext = hasNext();
                }
                return hasNext;
            }
        }

        @Override
        public ColMetaAndIndex next() {
            if (current == null) {
                current = new ColMetaAndIndex();
                Entry<String, ColumnHolder> entry = entryIterator.next();
                if (entry == null) {
                    throw new NoSuchElementException();
                } else {
                    current.setColumnHolder(entry.getValue());
                    current.setName(entry.getKey());
                    return current;
                }
            } else {
                ColumnHolder chNext = current.getColumnHolder().next;
                if (chNext != null) {
                    current.setColumnHolder(chNext);
                    return current;
                } else {
                    current = null;
                    return next();
                }
            }
        }

        @Override
        public void remove() {
            throw new IllegalStateException();

        }

    }

    @Override
    public Iterator<ColMetaAndIndex> indexIterator() {
        return new IndexMetaIterator(indexMap);
    }

    @Override
    public IRowsValueScaner scaner(List<ISelectable> columnsYouWant) {
        RowsValueScanerImp rvs = new RowsValueScanerImp(this, columnsYouWant);
        return rvs;
    }

    @Override
    public String toString() {
        return toStringWithInden(0);
    }

    @Override
    public boolean isSureLogicalIndexEqualActualIndex() {
        return this.isSureLogicalIndexEqualActualIndex;
    }

    @Override
    public void setIsSureLogicalIndexEqualActualIndex(boolean b) {
        this.isSureLogicalIndexEqualActualIndex = b;
    }

    public static ICursorMeta buildEmpty() {
        List<ColumnMeta> empty = Collections.emptyList();
        return new CursorMetaImp(empty);
    }

    @Override
    public ColumnMeta getColumnMeta(Integer index) {
        if (this.indexToColumnMeta.containsKey(index)) {
            return this.indexToColumnMeta.get(index);
        }

        throw new ExecutorException("index is not in cursor meta, indexToColumnMeta is " + this.indexToColumnMeta
                                    + ", " + this);

    }
}
