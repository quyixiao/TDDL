package com.taobao.tddl.optimizer.rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;

import com.google.common.collect.Lists;
import com.taobao.tddl.common.exception.TddlException;
import com.taobao.tddl.common.exception.TddlNestableRuntimeException;
import com.taobao.tddl.common.model.lifecycle.AbstractLifecycle;
import com.taobao.tddl.common.model.sqljep.Comparative;
import com.taobao.tddl.common.model.sqljep.ComparativeAND;
import com.taobao.tddl.common.model.sqljep.ComparativeBaseList;
import com.taobao.tddl.common.model.sqljep.ComparativeMapChoicer;
import com.taobao.tddl.common.model.sqljep.ComparativeOR;
import com.taobao.tddl.common.utils.GeneralUtil;
import com.taobao.tddl.optimizer.core.ASTNodeFactory;
import com.taobao.tddl.optimizer.core.datatype.DataType;
import com.taobao.tddl.optimizer.core.datatype.DataTypeUtil;
import com.taobao.tddl.optimizer.core.expression.IBindVal;
import com.taobao.tddl.optimizer.core.expression.IBooleanFilter;
import com.taobao.tddl.optimizer.core.expression.IColumn;
import com.taobao.tddl.optimizer.core.expression.IFilter;
import com.taobao.tddl.optimizer.core.expression.IFilter.OPERATION;
import com.taobao.tddl.optimizer.core.expression.IFunction;
import com.taobao.tddl.optimizer.core.expression.IGroupFilter;
import com.taobao.tddl.optimizer.core.expression.ILogicalFilter;
import com.taobao.tddl.optimizer.core.expression.ISelectable;
import com.taobao.tddl.optimizer.core.expression.bean.NullValue;
import com.taobao.tddl.optimizer.exception.OptimizerException;
import com.taobao.tddl.optimizer.utils.OptimizerUtils;
import com.taobao.tddl.rule.TableRule;
import com.taobao.tddl.rule.TddlRule;
import com.taobao.tddl.rule.exception.RouteCompareDiffException;
import com.taobao.tddl.rule.exception.TddlRuleException;
import com.taobao.tddl.rule.model.MatcherResult;
import com.taobao.tddl.rule.model.TargetDB;

/**
 * ??????????????????Tddl Rule????????????????????????????????????{@linkplain TddlRule}????????????????????????
 * 
 * @since 5.0.0
 */
public class OptimizerRule extends AbstractLifecycle {

    private final static int DEFAULT_OPERATION_COMP = -1000;
    private final TddlRule   tddlRule;

    public OptimizerRule(TddlRule tddlRule){
        this.tddlRule = tddlRule;
    }

    public List<TableRule> getTableRules() {
        return tddlRule.getTables();
    }

    @Override
    protected void doInit() throws TddlException {
        if (tddlRule != null && !tddlRule.isInited()) {
            tddlRule.init();
        }
    }

    @Override
    protected void doDestroy() throws TddlException {
        if (tddlRule != null && tddlRule.isInited()) {
            tddlRule.destroy();
        }
    }

    public List<TargetDB> shard(String logicTable, ComparativeMapChoicer choicer, boolean isWrite) {
        MatcherResult result;
        try {
            result = tddlRule.routeMverAndCompare(!isWrite, logicTable, choicer, Lists.newArrayList());
        } catch (RouteCompareDiffException e) {
            throw new TddlNestableRuntimeException(e);
        }

        List<TargetDB> targetDbs = result.getCalculationResult();
        if (targetDbs == null || targetDbs.isEmpty()) {
            throw new IllegalArgumentException("can't find target db. table is " + logicTable + ".");
        }

        return targetDbs;
    }

    /**
     * ????????????????????????????????????????????????
     */
    public List<TargetDB> shard(String logicTable, final IFilter ifilter, boolean isWrite,
                                boolean forceAllowFullTableScan) {
        MatcherResult result;
        try {
            result = tddlRule.routeMverAndCompare(!isWrite, logicTable, new ComparativeMapChoicer() {

                @Override
                public Map<String, Comparative> getColumnsMap(List<Object> arguments, Set<String> partnationSet) {
                    Map<String, Comparative> map = new HashMap<String, Comparative>();
                    for (String str : partnationSet) {
                        map.put(str, getColumnComparative(arguments, str));
                    }

                    return map;
                }

                @Override
                public Comparative getColumnComparative(List<Object> arguments, String colName) {
                    return getComparative(ifilter, colName);
                }
            }, Lists.newArrayList(), forceAllowFullTableScan);
        } catch (RouteCompareDiffException e) {
            throw new TddlNestableRuntimeException(e);
        }

        List<TargetDB> targetDbs = result.getCalculationResult();
        if (targetDbs == null || targetDbs.isEmpty()) {
            throw new IllegalArgumentException("can't find target db. table is " + logicTable + ". filter is "
                                               + ifilter);
        }

        return targetDbs;
    }

    /**
     * ???????????????????????????????????????????????????TargetDB
     * 
     * @param logicTable
     * @return
     */
    public TargetDB shardAny(String logicTable) {
        TableRule tableRule = getTableRule(logicTable);
        if (tableRule == null) {
            // ????????????????????????????????????
            TargetDB target = new TargetDB();
            target.setDbIndex(getDefaultDbIndex(logicTable));
            target.addOneTable(logicTable);
            return target;
        } else {
            for (String group : tableRule.getActualTopology().keySet()) {
                Set<String> tableNames = tableRule.getActualTopology().get(group);
                if (tableNames == null || tableNames.isEmpty()) {
                    continue;
                }

                TargetDB target = new TargetDB();
                target.setDbIndex(group);
                target.addOneTable(tableNames.iterator().next());
                return target;
            }
        }
        throw new IllegalArgumentException("can't find any target db. table is " + logicTable + ". ");
    }

    /**
     * ??????????????????????????????????????????????????????
     */
    public boolean isTableInSingleDb(String logicTable) {
        TableRule tableRule = getTableRule(logicTable);
        if (tableRule == null
            || (GeneralUtil.isEmpty(tableRule.getDbShardRules()) && GeneralUtil.isEmpty(tableRule.getTbShardRules()))) {
            // ???????????????????????????
            return true;
        }

        return false;
    }

    public String getDefaultDbIndex(String logicTable) {
        String defaultDb = tddlRule.getDefaultDbIndex(logicTable);
        if (defaultDb == null) {
            throw new TddlRuleException("defaultDbIndex is null");
        }
        return defaultDb;
    }

    public String getJoinGroup(String logicTable) {
        TableRule table = getTableRule(logicTable);
        return table != null ? table.getJoinGroup() : null;// ????????????????????????????????????
    }

    public boolean isBroadCast(String logicTable) {
        TableRule table = getTableRule(logicTable);
        return table != null ? table.isBroadcast() : false;// ????????????????????????????????????????????????????????????
    }

    public List<String> getSharedColumns(String logicTable) {
        TableRule table = getTableRule(logicTable);
        return table != null ? table.getShardColumns() : new ArrayList<String>();// ????????????????????????????????????
    }

    public TableRule getTableRule(String logicTable) {
        return tddlRule.getTable(logicTable);
    }

    /**
     * ???defaultDb??????????????????????????????????????????
     */
    public Set<String> mergeTableRule(List<String> defaultDbTables) {
        Set<String> result = new HashSet<String>();
        List<TableRule> tableRules = tddlRule.getTables();
        Map<String, String> dbIndexMap = tddlRule.getDbIndexMap();
        String defaultDb = tddlRule.getDefaultDbIndex();
        // // ???????????????????????????
        for (TableRule tableRule : tableRules) {
            String table = tableRule.getVirtualTbName();
            // ??????????????????????????????????????????tables???
            if (!StringUtils.contains(table, "._")) {
                result.add(table);
            }
        }
        for (Map.Entry<String, String> entry : dbIndexMap.entrySet()) {
            if (!entry.getValue().equals(defaultDb)) {
                // ??????????????????????????????????????????tables???
                if (!StringUtils.contains(entry.getKey(), "._")) {
                    result.add(entry.getKey());
                }
            }
        }
        // ?????????????????????
        for (String table : defaultDbTables) {
            boolean found = false;
            for (TableRule tableRule : tableRules) {
                if (tableRule.isActualTable(table)) {
                    found = true;
                    break;
                }
            }

            if (dbIndexMap.containsKey(table)) {
                found = true;
            }

            if (!found) {
                result.add(table);
            }
        }
        return result;
    }

    /**
     * ?????????{@linkplain IFilter}??????????????????Tddl Rule????????????{@linkplain Comparative}??????
     * 
     * @param ifilter
     * @param colName
     * @return
     */
    public static Comparative getComparative(IFilter ifilter, String colName) {
        // ????????????????????????????????????????????????
        if (ifilter == null) {
            return null;
        }

        if ("NOT".equalsIgnoreCase(ifilter.getFunctionName())) {
            return null;
        }

        if (ifilter instanceof ILogicalFilter) {
            if (ifilter.isNot()) {
                return null;
            }

            ComparativeBaseList comp = null;
            ILogicalFilter logicalFilter = (ILogicalFilter) ifilter;
            switch (ifilter.getOperation()) {
                case AND:
                    comp = new ComparativeAND();
                    break;
                case OR:
                    comp = new ComparativeOR();
                    break;
                default:
                    throw new IllegalArgumentException();
            }

            boolean isExistInAllSubFilter = true;
            for (Object sub : logicalFilter.getSubFilter()) {
                if (!(sub instanceof IFilter)) {
                    return null;
                }

                IFilter subFilter = (IFilter) sub;
                Comparative subComp = getComparative(subFilter, colName);// ??????
                if (subComp != null) {
                    comp.addComparative(subComp);
                }

                isExistInAllSubFilter &= (subComp != null);
            }

            if (comp == null || comp.getList() == null || comp.getList().isEmpty()) {
                return null;
            } else if (comp instanceof ComparativeOR && !isExistInAllSubFilter) {
                // ??????or????????????????????????????????????????????????????????????????????????????????????????????????null???
                // ??????????????????id?????????????????? id = 1 or id = 3???????????????
                // ???????????????id = 1 or name = 2???????????????????????????
                return null;
            } else if (comp.getList().size() == 1) {
                return comp.getList().get(0);// ????????????????????????and
            }

            return comp;
        } else if (ifilter instanceof IGroupFilter) {
            if (ifilter.isNot()) {
                return null;
            }

            IGroupFilter groupFilter = (IGroupFilter) ifilter;
            ComparativeBaseList comp = new ComparativeOR();
            boolean isExistInAllSubFilter = true;
            for (Object sub : groupFilter.getSubFilter()) {
                if (!(sub instanceof IFilter)) {
                    return null;
                }

                IFilter subFilter = (IFilter) sub;
                Comparative subComp = getComparative(subFilter, colName);// ??????
                if (subComp != null) {
                    comp.addComparative(subComp);
                }

                isExistInAllSubFilter &= (subComp != null);
            }

            if (comp == null || comp.getList() == null || comp.getList().isEmpty()) {
                return null;
            } else if (comp instanceof ComparativeOR && !isExistInAllSubFilter) {
                // ??????or????????????????????????????????????????????????????????????????????????????????????????????????null???
                // ??????????????????id?????????????????? id = 1 or id = 3???????????????
                // ???????????????id = 1 or name = 2???????????????????????????
                return null;
            } else if (comp.getList().size() == 1) {
                return comp.getList().get(0);// ????????????????????????and
            }

            return comp;
        } else if (ifilter instanceof IBooleanFilter) {
            Comparative comp = null;
            IBooleanFilter booleanFilter = (IBooleanFilter) ifilter;

            // ????????????
            if (isNull(booleanFilter.getColumn())
                || (isNull(booleanFilter.getValue()) && isNull(booleanFilter.getValues()))) {
                return null;
            }

            Object column = convertNowFunction(booleanFilter.getColumn());
            Object value = convertNowFunction(booleanFilter.getValue());
            // ??????????????? A > B , A > B + 1
            if (column instanceof ISelectable && value instanceof ISelectable) {
                return null;
            }

            // ???????????????????????????
            if (!(column instanceof IColumn || value instanceof IColumn)) {
                return null;
            }

            if (booleanFilter.isNot()) {
                return null;
            }

            if (booleanFilter.getOperation() == OPERATION.IN) {// in????????????isReverse
                DataType type = null;
                if (booleanFilter.getColumn() instanceof IColumn) {
                    type = ((IColumn) booleanFilter.getColumn()).getDataType();
                }

                ComparativeBaseList orComp = new ComparativeOR();
                for (Object v : booleanFilter.getValues()) {
                    IBooleanFilter ef = ASTNodeFactory.getInstance().createBooleanFilter();
                    ef.setOperation(OPERATION.EQ);
                    ef.setColumn(booleanFilter.getColumn());
                    ef.setValue(getValue(v, type));

                    Comparative subComp = getComparative(ef, colName);
                    if (subComp != null) {
                        orComp.addComparative(subComp);
                    }
                }

                if (orComp.getList().isEmpty()) {// ??????????????????
                    return null;
                }

                return orComp;
            } else {
                int operationComp = DEFAULT_OPERATION_COMP;
                switch (booleanFilter.getOperation()) {
                    case GT:
                        operationComp = Comparative.GreaterThan;
                        break;
                    case EQ:
                        operationComp = Comparative.Equivalent;
                        break;
                    case GT_EQ:
                        operationComp = Comparative.GreaterThanOrEqual;
                        break;
                    case LT:
                        operationComp = Comparative.LessThan;
                        break;
                    case LT_EQ:
                        operationComp = Comparative.LessThanOrEqual;
                        break;
                    default:
                        return null;
                }

                IColumn col = null;
                Object val = null;
                if (booleanFilter.getColumn() instanceof IColumn) {
                    col = OptimizerUtils.getColumn(column);
                    val = getValue(value, col.getDataType());
                } else {// ?????? 1 = id ?????????
                    col = OptimizerUtils.getColumn(value);
                    val = getValue(column, col.getDataType());
                    operationComp = Comparative.exchangeComparison(operationComp); // ????????????
                }

                if (colName.equalsIgnoreCase(col.getColumnName()) && operationComp != DEFAULT_OPERATION_COMP) {
                    if (!(value instanceof Comparable)) {
                        throw new OptimizerException("type: " + value.getClass().getSimpleName()
                                                     + " is not comparable, cannot be used in partition column");
                    }
                    comp = new Comparative(operationComp, (Comparable) val);
                }

                return comp;
            }
        } else {
            // ???null,????????????
            return null;
        }
    }

    private static boolean isNull(Object val) {
        if (val == null) {
            return true;
        } else if (val instanceof NullValue) {
            return true;
        } else if (val instanceof Collection) {
            boolean isn = true;
            for (Object obj : (Collection) val) {
                isn &= isNull(obj);
            }

            return isn;
        }

        return false;
    }

    /**
     * ?????????now???????????????date??????
     */
    private static Object convertNowFunction(Object val) {
        if (val instanceof IFunction) {
            IFunction func = (IFunction) val;
            if ("NOW".equalsIgnoreCase(func.getFunctionName())) {
                return new Date();
            }
        }

        return val;
    }

    private static Object getValue(Object val, DataType type) {
        if (val instanceof IBindVal) {
            // ??????batch???????????????BindVal
            return ((IBindVal) val).getValue();
        } else if (DataTypeUtil.isDateType(type)) {
            // ?????????????????????????????????????????????
            return type.convertFrom(val);
        }

        return val;
    }

}
