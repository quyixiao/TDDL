package com.taobao.tddl.optimizer.costbased;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;

import com.alibaba.cobar.parser.ast.stmt.SQLStatement;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.taobao.tddl.client.RouteCondition;
import com.taobao.tddl.common.TddlConstants;
import com.taobao.tddl.common.client.util.ThreadLocalMap;
import com.taobao.tddl.common.exception.NotSupportException;
import com.taobao.tddl.common.exception.TddlException;
import com.taobao.tddl.common.exception.TddlNestableRuntimeException;
import com.taobao.tddl.common.jdbc.ParameterContext;
import com.taobao.tddl.common.jdbc.Parameters;
import com.taobao.tddl.common.jdbc.SqlTypeParser;
import com.taobao.tddl.common.model.Group;
import com.taobao.tddl.common.model.Group.GroupType;
import com.taobao.tddl.common.model.SqlType;
import com.taobao.tddl.common.model.hint.DirectlyRouteCondition;
import com.taobao.tddl.common.model.hint.RuleRouteCondition;
import com.taobao.tddl.common.model.lifecycle.AbstractLifecycle;
import com.taobao.tddl.common.properties.ConnectionProperties;
import com.taobao.tddl.common.utils.GeneralUtil;
import com.taobao.tddl.monitor.Monitor;
import com.taobao.tddl.monitor.eagleeye.EagleeyeHelper;
import com.taobao.tddl.optimizer.Optimizer;
import com.taobao.tddl.optimizer.OptimizerContext;
import com.taobao.tddl.optimizer.core.ASTNodeFactory;
import com.taobao.tddl.optimizer.core.ast.ASTNode;
import com.taobao.tddl.optimizer.core.ast.DMLNode;
import com.taobao.tddl.optimizer.core.ast.QueryTreeNode;
import com.taobao.tddl.optimizer.core.ast.build.MergeNodeBuilder;
import com.taobao.tddl.optimizer.core.ast.build.QueryTreeNodeBuilder;
import com.taobao.tddl.optimizer.core.ast.dal.BaseShowNode;
import com.taobao.tddl.optimizer.core.ast.dal.BaseShowNode.ShowType;
import com.taobao.tddl.optimizer.core.ast.dal.ShowWithTableNode;
import com.taobao.tddl.optimizer.core.ast.dml.DeleteNode;
import com.taobao.tddl.optimizer.core.ast.dml.InsertNode;
import com.taobao.tddl.optimizer.core.ast.dml.PutNode;
import com.taobao.tddl.optimizer.core.ast.dml.UpdateNode;
import com.taobao.tddl.optimizer.core.ast.query.JoinNode;
import com.taobao.tddl.optimizer.core.ast.query.MergeNode;
import com.taobao.tddl.optimizer.core.ast.query.QueryNode;
import com.taobao.tddl.optimizer.core.ast.query.TableNode;
import com.taobao.tddl.optimizer.core.expression.IFunction;
import com.taobao.tddl.optimizer.core.plan.IDataNodeExecutor;
import com.taobao.tddl.optimizer.core.plan.IPut;
import com.taobao.tddl.optimizer.core.plan.IQueryTree;
import com.taobao.tddl.optimizer.core.plan.query.IMerge;
import com.taobao.tddl.optimizer.costbased.after.ChooseTreadOptimizer;
import com.taobao.tddl.optimizer.costbased.after.FillLastSequenceValOptimizer;
import com.taobao.tddl.optimizer.costbased.after.FillRequestIDAndSubRequestID;
import com.taobao.tddl.optimizer.costbased.after.FuckAvgOptimizer;
import com.taobao.tddl.optimizer.costbased.after.MergeConcurrentOptimizer;
import com.taobao.tddl.optimizer.costbased.after.QueryPlanOptimizer;
import com.taobao.tddl.optimizer.costbased.after.StreamingOptimizer;
import com.taobao.tddl.optimizer.costbased.chooser.DataNodeChooser;
import com.taobao.tddl.optimizer.costbased.chooser.IndexChooser;
import com.taobao.tddl.optimizer.costbased.chooser.JoinChooser;
import com.taobao.tddl.optimizer.costbased.pusher.FilterPusher;
import com.taobao.tddl.optimizer.costbased.pusher.OrderByPusher;
import com.taobao.tddl.optimizer.exception.EmptyResultFilterException;
import com.taobao.tddl.optimizer.exception.OptimizerException;
import com.taobao.tddl.optimizer.parse.SqlAnalysisResult;
import com.taobao.tddl.optimizer.parse.SqlParseManager;
import com.taobao.tddl.optimizer.parse.cobar.CobarSqlAnalysisResult;
import com.taobao.tddl.optimizer.parse.cobar.CobarSqlParseManager;
import com.taobao.tddl.optimizer.parse.cobar.visitor.MysqlSqlVisitor;
import com.taobao.tddl.optimizer.parse.hint.SimpleHintParser;
import com.taobao.tddl.optimizer.rule.OptimizerRule;
import com.taobao.tddl.rule.TableRule;
import com.taobao.tddl.rule.model.TargetDB;

import com.taobao.tddl.common.utils.logger.Logger;
import com.taobao.tddl.common.utils.logger.LoggerFactory;

/**
 * <pre>
 * ?????????????????????????????????????????????????????????????????????:
 * 1. ???????????????
 *    a.  join??????,?????????{@linkplain JoinPreProcessor}
 *    b.  filter??????,?????????{@linkplain FilterPreProcessor}
 * 2. ????????????
 *    a.  filter??????,?????????{@linkplain FilterPusher}
 *    b.  order??????,?????????{@linkplain OrderByPusher}
 *    c.  ???????????????,?????????{@linkplain QueryTreeNodeBuilder}
 * 3. ????????????
 *    a.  ????????????,?????????{@linkplain TableNode}.convertToJoinIfNeed()
 *    b.  ????????????,?????????{@linkplain IndexChooser}
 *    c.  filter??????,?????????{@linkplain FilterSpliter}
 * 4. join??????
 *    a.  join????????????,?????????{@linkplain JoinChooser}
 *    b.  ????????????join??????,{@linkplain JoinNode}.convertToJoinIfNeed()
 *    c.  join????????????,?????????{@linkplain JoinChooser}
 * 5. shard??????(????????????)
 *    a.  MergeNode??????,?????????{@linkplain DataNodeChooser}
 *    b.  ????????????,?????????{@linkplain MergeNodeBuilder}
 * </pre>
 * 
 * @author Dreamond
 * @author <a href="jianghang.loujh@taobao.com">jianghang</a>
 * @since 5.0.0
 */
public class CostBasedOptimizer extends AbstractLifecycle implements Optimizer {

    private static final String            _DIRECT         = "_DIRECT_";
    private static final Logger            logger          = LoggerFactory.getLogger(CostBasedOptimizer.class);
    private long                           cacheSize       = 1000;
    private long                           expireTime      = TddlConstants.DEFAULT_OPTIMIZER_EXPIRE_TIME;
    private final List<QueryPlanOptimizer> afterOptimizers = new ArrayList<QueryPlanOptimizer>();
    private SqlParseManager                sqlParseManager;
    private Cache<String, OptimizeResult>  optimizedResults;
    private OptimizerRule                  optimizerRule;

    public CostBasedOptimizer(OptimizerRule optimizerRule){
        this.optimizerRule = optimizerRule;
    }

    @Override
    protected void doInit() throws TddlException {
        // after??????
        afterOptimizers.add(new FuckAvgOptimizer());
        afterOptimizers.add(new ChooseTreadOptimizer());
        afterOptimizers.add(new FillRequestIDAndSubRequestID());
        // afterOptimizers.add(new MergeJoinMergeOptimizer());
        afterOptimizers.add(new MergeConcurrentOptimizer());
        afterOptimizers.add(new StreamingOptimizer());
        afterOptimizers.add(new FillLastSequenceValOptimizer());

        if (this.sqlParseManager == null) {
            CobarSqlParseManager sqlParseManager = new CobarSqlParseManager();
            sqlParseManager.setCacheSize(cacheSize);
            sqlParseManager.setExpireTime(expireTime);
            this.sqlParseManager = sqlParseManager;
        }

        if (!sqlParseManager.isInited()) {
            sqlParseManager.init(); // ??????
        }

        optimizedResults = CacheBuilder.newBuilder()
            .maximumSize(cacheSize)
            .expireAfterWrite(expireTime, TimeUnit.MILLISECONDS)
            .softValues()
            .build();
    }

    @Override
    protected void doDestroy() throws TddlException {
        optimizedResults.invalidateAll();
        sqlParseManager.destroy();
    }

    private class OptimizeResult {

        public ASTNode                      optimized = null;
        public DirectlyRouteCondition       hint      = null; // ????????????
        public TddlNestableRuntimeException ex        = null;
    }

    @Override
    public ASTNode optimizeAst(ASTNode node, Parameters parameterSettings, Map<String, Object> extraCmd)
                                                                                                        throws OptimizerException {
        return (ASTNode) optimizeAstOrHint(node, parameterSettings, extraCmd, null, false);
    }

    @Override
    public Object optimizeAstOrHint(String sql, Parameters parameterSettings, boolean cached,
                                    Map<String, Object> extraCmd) throws OptimizerException {
        // ??????sql hint????????????
        Map<Integer, ParameterContext> params = null;
        if (parameterSettings != null) {
            params = parameterSettings.getFirstParameter();
        }

        RouteCondition routeCondition = SimpleHintParser.convertHint2RouteCondition(sql, params);
        if (routeCondition != null && !routeCondition.getExtraCmds().isEmpty()) {
            // ??????sql??????extra cmd??????
            if (extraCmd == null) {
                extraCmd = new HashMap<String, Object>();
            }

            extraCmd.putAll(routeCondition.getExtraCmds());
        }

        if (routeCondition != null
            && (routeCondition instanceof DirectlyRouteCondition || routeCondition instanceof RuleRouteCondition)) {
            if (parameterSettings != null && parameterSettings.isBatch()) {
                // ????????????hint + batch??????
                List<RouteCondition> routeConditions = new ArrayList<RouteCondition>();
                String newSql = null;
                for (Map<Integer, ParameterContext> param : parameterSettings.getBatchParameters()) {
                    routeConditions.add(SimpleHintParser.convertHint2RouteCondition(sql, param));
                    // ?????????????????????????????????????????????hint??????????????????
                    newSql = SimpleHintParser.removeHint(sql, param);
                }

                return optimizerHint(newSql, cached, routeConditions, parameterSettings, extraCmd);
            } else {
                // ??????hint + ?????????
                String newSql = SimpleHintParser.removeHint(sql, params);
                return optimizerHint(newSql, cached, routeCondition, parameterSettings, extraCmd);
            }
        } else {
            return optimizeAstOrHint(null, parameterSettings, extraCmd, sql, cached);
        }
    }

    @Override
    public IFunction assignmentSubquery(ASTNode node, Map<Long, Object> subquerySettings, Map<String, Object> extraCmd)
                                                                                                                       throws OptimizerException {
        if (node instanceof QueryTreeNode) {
            return SubQueryPreProcessor.assignmentSubqueryOnFilter((QueryTreeNode) node, subquerySettings);
        } else if (node instanceof DMLNode) {
            return SubQueryPreProcessor.assignmentSubqueryOnFilter(((DMLNode) node).getNode(), subquerySettings);
        }

        return null;
    }

    @Override
    public IDataNodeExecutor optimizePlan(ASTNode node, Parameters parameterSettings, Map<String, Object> extraCmd)
                                                                                                                   throws OptimizerException {
        try {
            // ??????batch??????sequence??????
            if (parameterSettings != null && parameterSettings.isBatch()) {
                SequencePreProcessor.opitmize(node, parameterSettings, extraCmd);
            }
            // ???????????????????????????
            node = DataNodeChooser.shard(node, parameterSettings, extraCmd);
            node = this.createMergeForJoin(node, extraCmd);
            if (node instanceof QueryTreeNode) {
                OrderByPusher.optimize((QueryTreeNode) node);
            }

            IDataNodeExecutor plan = node.toDataNodeExecutor();
            // ????????????????????????????????????
            for (QueryPlanOptimizer after : afterOptimizers) {
                plan = after.optimize(plan, parameterSettings, extraCmd);
            }
            return plan;
        } finally {
            // ??????last_sequence_id???????????????
            ThreadLocalMap.remove(IDataNodeExecutor.LAST_SEQUENCE_VAL);
        }
    }

    public IDataNodeExecutor optimizeAndAssignment(ASTNode node, Parameters parameterSettings,
                                                   Map<String, Object> extraCmd) throws OptimizerException {
        ASTNode optimized = optimizeAst(node, parameterSettings, extraCmd);
        return optimizePlan(optimized, parameterSettings, extraCmd);
    }

    /**
     * ??????sql?????????????????????+?????? , cache???????????????????????????????????????????????????
     */
    public IDataNodeExecutor optimizeAndAssignment(String sql, Parameters parameterSettings,
                                                   Map<String, Object> extraCmd, boolean cached)
                                                                                                throws OptimizerException {
        Object obj = optimizeAstOrHint(sql, parameterSettings, cached, extraCmd);
        if (obj instanceof IDataNodeExecutor) {
            return (IDataNodeExecutor) obj;
        } else {
            return optimizePlan((ASTNode) obj, parameterSettings, extraCmd);
        }
    }

    private IDataNodeExecutor optimizerHint(String sql, boolean cached, RouteCondition routeCondition,
                                            Parameters parameterSettings, Map<String, Object> extraCmd) {
        return optimizerHint(sql, cached, Arrays.asList(routeCondition), parameterSettings, extraCmd);
    }

    private IDataNodeExecutor optimizerHint(String sql, boolean cached, List<RouteCondition> routeConditions,
                                            Parameters parameterSettings, Map<String, Object> extraCmd) {
        long time = System.currentTimeMillis();
        List<IDataNodeExecutor> plans = new ArrayList<IDataNodeExecutor>();
        String groupHint = SimpleHintParser.extractTDDLGroupHintString(sql);
        // ??????hint????????????????????????
        int index = -1; // -1????????????batch
        if (routeConditions.size() > 1) {
            index = 0;
        }
        for (RouteCondition routeCondition : routeConditions) {
            if (routeCondition instanceof DirectlyRouteCondition) {
                DirectlyRouteCondition drc = (DirectlyRouteCondition) routeCondition;
                if (!drc.getTables().isEmpty()) {
                    SqlAnalysisResult sqlAnalysisResult = sqlParseManager.parse(sql, cached);
                    Map<String, String> sqls = buildDirectSqls(sqlAnalysisResult,
                        drc.getVirtualTableName(),
                        drc.getTables(),
                        groupHint);
                    plans.addAll(buildDirectPlan(sqlAnalysisResult.getSqlType(), drc.getDbId(), sqls, index));
                } else {
                    // ????????????sql??????????????????sql??????
                    Map<String, String> sqls = new HashMap<String, String>();
                    sqls.put(_DIRECT, sql);
                    plans.addAll(buildDirectPlan(SqlTypeParser.getSqlType(sql), drc.getDbId(), sqls, index));
                }
            } else if (routeCondition instanceof RuleRouteCondition) {
                RuleRouteCondition rrc = (RuleRouteCondition) routeCondition;
                SqlAnalysisResult sqlAnalysisResult = sqlParseManager.parse(sql, cached);
                boolean isWrite = (sqlAnalysisResult.getSqlType() != SqlType.SELECT && sqlAnalysisResult.getSqlType() != SqlType.SELECT_FOR_UPDATE);
                List<TargetDB> targetDBs = OptimizerContext.getContext()
                    .getRule()
                    .shard(rrc.getVirtualTableName(), rrc.getCompMapChoicer(), isWrite);
                // ???????????????????????????
                Set<String> tables = new HashSet<String>();
                for (TargetDB target : targetDBs) {
                    tables.addAll(target.getTableNames());
                }
                Map<String, String> sqls = buildDirectSqls(sqlAnalysisResult,
                    rrc.getVirtualTableName(),
                    tables,
                    groupHint);
                plans.addAll(buildRulePlain(sqlAnalysisResult.getSqlType(), targetDBs, sqls, index));
            } else {
                throw new NotSupportException("RouteCondition : " + routeCondition.toString());
            }

            index++;
        }

        // ??????????????????
        IDataNodeExecutor qc = null;
        if (index > 0) {
            // ??????batch + hint
            Map<List<String>, IDataNodeExecutor> indexs = new HashMap<List<String>, IDataNodeExecutor>();
            for (IDataNodeExecutor plan : plans) {
                if (plan instanceof IQueryTree) {
                    throw new OptimizerException("????????????select?????????batch??????");
                }

                List<String> dbAndSqls = Arrays.asList(plan.getDataNode(), plan.getSql());
                IDataNodeExecutor ine = indexs.get(dbAndSqls);
                if (ine == null) {
                    indexs.put(dbAndSqls, plan);
                } else {
                    // ??????db + sql?????????, ?????????batchIndexs??????????????????????????????
                    ((IPut) ine).getBatchIndexs().addAll(((IPut) plan).getBatchIndexs());
                }
            }

            if (indexs.size() == 1) {
                qc = indexs.values().iterator().next();
            } else {
                IMerge merge = ASTNodeFactory.getInstance().createMerge();
                for (IDataNodeExecutor plan : indexs.values()) {
                    merge.addSubNode(plan);
                }

                merge.executeOn(merge.getSubNode().getDataNode()); // ???????????????
                qc = merge;
            }
        } else {
            if (plans.size() == 1) {
                qc = plans.get(0);
                // ??????????????????hint?????????????????????batch
                // ????????????????????????hint
                if (parameterSettings != null && parameterSettings.isBatch()) {
                    if (qc instanceof IQueryTree) {
                        throw new OptimizerException("????????????select?????????batch??????");
                    }

                    int batchSize = parameterSettings.getBatchParameters().size();
                    List<Integer> batchIndexs = new ArrayList<Integer>();
                    for (int i = 0; i < batchSize; i++) {
                        batchIndexs.add(i);
                    }
                    ((IPut) qc).setBatchIndexs(batchIndexs);
                }
            } else {
                IMerge merge = ASTNodeFactory.getInstance().createMerge();
                for (IDataNodeExecutor plan : plans) {
                    merge.addSubNode(plan);
                }

                merge.executeOn(plans.get(0).getDataNode()); // ???????????????
                qc = merge;
            }
        }

        // ????????????????????????????????????
        for (QueryPlanOptimizer after : afterOptimizers) {
            qc = after.optimize(qc, parameterSettings, extraCmd);
        }
        if (logger.isDebugEnabled()) {
            logger.debug(qc.toString());
        }

        time = Monitor.monitorAndRenewTime(Monitor.KEY1,
            Monitor.KEY2_TDDL_OPTIMIZER,
            Monitor.Key3Success,
            System.currentTimeMillis() - time);
        return qc;
    }

    private Object optimizeAstOrHint(final ASTNode node, final Parameters parameterSettings,
                                     final Map<String, Object> extraCmd, final String sql, final boolean cached) {
        long time = System.currentTimeMillis();
        ASTNode optimized = null;
        if (cached && sql != null && !sql.isEmpty()) {
            OptimizeResult or;
            try {
                or = optimizedResults.get(sql, new Callable<OptimizeResult>() {

                    @Override
                    public OptimizeResult call() throws Exception {
                        OptimizeResult or = new OptimizeResult();
                        try {
                            // ????????????????????????,?????????????????????????????????
                            SqlAnalysisResult result = sqlParseManager.parse(sql, false);
                            DirectlyRouteCondition hint = singleDbPreProcess(result, extraCmd);
                            if (hint != null) {
                                or.hint = hint;
                            } else {
                                // ???????????????????????????
                                or.optimized = optimize(result.getAstNode(), parameterSettings, extraCmd);
                            }
                        } catch (Exception e) {
                            if (e instanceof TddlNestableRuntimeException) {
                                or.ex = (TddlNestableRuntimeException) e;
                            } else {
                                or.ex = new TddlNestableRuntimeException(e);
                            }
                        }
                        return or;
                    }
                });
            } catch (ExecutionException e1) {
                throw new OptimizerException("optimizer is interrupt");
            }

            if (or.ex != null) {
                throw or.ex;
            } else if (or.hint != null) {
                // ?????????????????????hint??????????????????????????????
                return optimizerHint(sql, cached, or.hint, parameterSettings, extraCmd);
            } else {
                optimized = or.optimized.deepCopy();
                optimized.build();
            }
        } else {
            if (node == null) {
                SqlAnalysisResult result = sqlParseManager.parse(sql, false);
                DirectlyRouteCondition hint = singleDbPreProcess(result, extraCmd);
                if (hint != null) {
                    // ??????hint????????????
                    return optimizerHint(sql, cached, hint, parameterSettings, extraCmd);
                } else {
                    // ???????????????????????????
                    optimized = optimize(result.getAstNode(), parameterSettings, extraCmd);
                }
            } else {
                optimized = this.optimize(node, parameterSettings, extraCmd);
            }
        }

        time = Monitor.monitorAndRenewTime(Monitor.KEY1,
            Monitor.KEY2_TDDL_OPTIMIZER,
            Monitor.Key3Success,
            System.currentTimeMillis() - time);
        return optimized;
    }

    /**
     * ????????????????????????????????????????????????????????????,?????????????????????TableNode?????????????????????(???????????????)
     * 
     * @param node
     * @return
     */
    private DirectlyRouteCondition singleDbPreProcess(SqlAnalysisResult sqlResult, Map<String, Object> extraCmd) {
        if (!sqlResult.isAstNode()) {
            // ???????????????sql??????????????????default??????????????????
            // 1. select 1+1
            // 2. select now() from dual
            // 3. show create table xxxx;
            return new DirectlyRouteCondition(optimizerRule.getDefaultDbIndex(null));
        }

        Map<String, String> tableNames = sqlResult.getTableNames();
        for (Map.Entry<String, String> entry : tableNames.entrySet()) {
            // ???????????????????????????,????????????
            if (StringUtils.equalsIgnoreCase("information_schema", entry.getValue())) {
                return new DirectlyRouteCondition(optimizerRule.getDefaultDbIndex(null));
            }
        }

        ASTNode astNode = sqlResult.getAstNode();
        if (astNode instanceof DMLNode) {
            boolean processAutoIncrement = GeneralUtil.getExtraCmdBoolean(extraCmd,
                ConnectionProperties.PROCESS_AUTO_INCREMENT_BY_SEQUENCE,
                true);
            ((DMLNode) astNode).setProcessAutoIncrement(processAutoIncrement);
        }
        astNode.build();
        if (sqlResult.getSqlType() == SqlType.SELECT_LAST_INSERT_ID) {
            astNode.setSql(sqlResult.getSql());
            astNode.executeOn(IDataNodeExecutor.USE_LAST_DATA_NODE);
            return null;
        } else if (sqlResult.getSqlType() == SqlType.TDDL_SHOW) {
            BaseShowNode showNode = (BaseShowNode) astNode;
            ShowType type = showNode.getType();
            switch (type) {
                case CREATE_TABLE:
                case DESC:
                case INDEX:
                case INDEXES:
                case KEYS:
                case COLUMNS:
                    String tableName = ((ShowWithTableNode) showNode).getTableName();
                    // ???????????????????????????????????????
                    TargetDB target = OptimizerContext.getContext().getRule().shardAny(tableName);
                    ((ShowWithTableNode) showNode).setActualTableName(target.getTableNames().iterator().next());
                    astNode.executeOn(target.getDbIndex());
                    break;
                case TABLES:
                    astNode.executeOn(optimizerRule.getDefaultDbIndex(null));
                    break;
                default:
                    astNode.executeOn(IDataNodeExecutor.DUAL_GROUP);
                    break;
            }

            return null;
        } else if (sqlResult.getSqlType() == SqlType.GET_SEQUENCE) {
            astNode.executeOn(IDataNodeExecutor.DUAL_GROUP);
            return null;
        }

        if (astNode.isExistSequenceVal() || (astNode instanceof DMLNode && ((DMLNode) astNode).processAutoIncrement())) { // ?????????seq?????????????????????hint??????
            return null;
        }

        String lastDbIndex = null;
        StringBuilder vtabs = new StringBuilder();
        StringBuilder wtabs = new StringBuilder();
        int i = 0;
        boolean existBroadCast = false;
        boolean isAllbroadcast = true;
        for (String tableName : tableNames.keySet()) {
            vtabs.append(tableName);
            if (i < sqlResult.getTableNames().size() - 1) {
                vtabs.append(',');
            }
            TableRule tableRule = optimizerRule.getTableRule(tableName);
            if (tableRule != null) {
                isAllbroadcast &= tableRule.isBroadcast();
                existBroadCast |= tableRule.isBroadcast();
            }

            if (tableRule == null) {
                String dbIndex = optimizerRule.getDefaultDbIndex(tableName);
                // ?????????????????????dbIndex????????????
                if (lastDbIndex == null || lastDbIndex.equals(dbIndex)) {
                    lastDbIndex = dbIndex;
                } else {
                    return null;
                }

                wtabs.append(tableName);
                if (i < sqlResult.getTableNames().size() - 1) {
                    wtabs.append(',');
                }
            } else if (GeneralUtil.isEmpty(tableRule.getDbShardRules())
                       && GeneralUtil.isEmpty(tableRule.getTbShardRules())) {
                // ????????????????????????
                String dbIndex = tableRule.getDbNamePattern();
                if (lastDbIndex == null || lastDbIndex.equals(dbIndex)) {
                    lastDbIndex = dbIndex;
                } else {
                    return null;
                }

                wtabs.append(tableRule.getTbNamePattern()); // ????????????????????????
                if (i < sqlResult.getTableNames().size() - 1) {
                    wtabs.append(',');
                }
            } else {
                return null;
            }

            i++;
        }

        if (existBroadCast && isAllbroadcast) {
            return null; // ????????????????????????????????????
        }

        if (lastDbIndex != null) {
            Group group = OptimizerContext.getContext().getMatrix().getGroup(lastDbIndex);
            if (group != null && group.getType() == GroupType.MYSQL_JDBC) {
                // ?????????mysql???????????????
                String vtab = vtabs.toString();
                String wtab = wtabs.toString();
                DirectlyRouteCondition condition = new DirectlyRouteCondition(lastDbIndex);
                if (!StringUtils.equalsIgnoreCase(vtab, wtab)) {
                    // ?????????????????????????????????
                    condition.setVirtualTableName(vtab);
                    condition.addTable(wtab);
                }
                return condition;
            }
        }

        return null;
    }

    private ASTNode optimize(ASTNode node, Parameters parameterSettings, Map<String, Object> extraCmd) {
        // ???????????????build?????????select?????????????????????
        node.build();
        ASTNode optimized = node;
        try {
            if (node instanceof QueryTreeNode) {
                optimized = this.optimizeQuery((QueryTreeNode) node, extraCmd);
            }

            if (node instanceof InsertNode) {
                optimized = this.optimizeInsert((InsertNode) node, extraCmd);
            }

            else if (node instanceof DeleteNode) {
                optimized = this.optimizeDelete((DeleteNode) node, extraCmd);
            }

            else if (node instanceof UpdateNode) {
                optimized = this.optimizeUpdate((UpdateNode) node, extraCmd);
            }

            else if (node instanceof PutNode) {
                optimized = this.optimizePut((PutNode) node, extraCmd);
            }

        } catch (EmptyResultFilterException e) {
            e.setAstNode(optimized); // ???????????????
            throw e;
        }

        return optimized;
    }

    private QueryTreeNode optimizeQuery(QueryTreeNode qn, Map<String, Object> extraCmd) {
        // ????????????????????????????????????????????????????????????????????????
        // filter?????????????????????????????????
        List<IFunction> funcs = SubQueryPreProcessor.findAllSubqueryOnFilter(qn, true);
        for (IFunction func : funcs) {
            QueryTreeNode query = (QueryTreeNode) func.getArgs().get(0);
            query = this.optimizeQuery(query, extraCmd);
            func.getArgs().set(0, query);
        }

        if (funcs.size() > 0) {
            // ????????????????????????????????????????????????
            funcs = SubQueryPreProcessor.findAllSubqueryOnFilter(qn, false);
            if (funcs.size() > 0) {
                return qn;
            }
        }

        // ????????????join
        qn = JoinPreProcessor.optimize(qn);

        // ?????????filter????????????????????????/?????????
        qn = FilterPreProcessor.optimize(qn, true, extraCmd);

        // ?????????subquery filter
        qn = SubQueryPreProcessor.opitmize(qn);

        // ??????????????????????????????
        qn = FilterPusher.optimize(qn);

        // ??????????????????????????????????????????
        qn = JoinChooser.optimize(qn, extraCmd);

        // ????????????build
        qn.build();
        return qn;
    }

    private ASTNode optimizeUpdate(UpdateNode update, Map<String, Object> extraCmd) {
        update.build();
        if (extraCmd == null) {
            extraCmd = new HashMap();
        }
        // update????????????????????????
        extraCmd.put(ConnectionProperties.CHOOSE_INDEX, "FALSE");
        QueryTreeNode queryCommon = this.optimizeQuery(update.getNode(), extraCmd);
        queryCommon.build();
        update.setNode((TableNode) queryCommon);
        return update;

    }

    private ASTNode optimizeInsert(InsertNode insert, Map<String, Object> extraCmd) {
        insert.setNode((TableNode) insert.getNode().convertToJoinIfNeed());
        if (insert.getSelectNode() != null) {
            insert.setSelectNode(optimizeQuery(insert.getSelectNode(), extraCmd));
        }
        return insert;
    }

    private ASTNode optimizeDelete(DeleteNode delete, Map<String, Object> extraCmd) {
        QueryTreeNode queryCommon = this.optimizeQuery(delete.getNode(), extraCmd);
        delete.setNode((TableNode) queryCommon);
        return delete;
    }

    private ASTNode optimizePut(PutNode put, Map<String, Object> extraCmd) {
        put.setNode((TableNode) put.getNode().convertToJoinIfNeed());
        if (put.getSelectNode() != null) {
            put.setSelectNode(optimizeQuery(put.getSelectNode(), extraCmd));
        }
        return put;
    }

    // ============= helper method =============

    /**
     * ??????visitor??????????????????sql
     */
    private Map<String, /* table name */String/* sql */> buildDirectSqls(SqlAnalysisResult sqlAnalysisResult,
                                                                         String vtab, Collection<String> tables,
                                                                         String groupHint) {
        if (groupHint == null) {
            groupHint = "";
        }
        Map<String, String> sqls = new HashMap<String, String>();
        // ?????????????????????????????????sql
        // ???????????????????????????????????????????????????
        SQLStatement statement = ((CobarSqlAnalysisResult) sqlAnalysisResult).getStatement();
        if (StringUtils.isNotEmpty(vtab) && !tables.isEmpty()) {
            String[] vtabs = StringUtils.split(vtab, ',');
            Map<String, String> logicTable2RealTable = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            for (String realTable : tables) {
                String[] rtabs = StringUtils.split(realTable, ',');
                if (rtabs.length != vtabs.length) {
                    throw new OptimizerException("hint???????????????????????????????????????");
                }
                int i = 0;
                for (String v : vtabs) {
                    logicTable2RealTable.put(v, rtabs[i++]);
                }
                MysqlSqlVisitor sqlVisitor = new MysqlSqlVisitor(new StringBuilder(groupHint), logicTable2RealTable);
                statement.accept(sqlVisitor);
                sqls.put(realTable, sqlVisitor.getSql());
            }
        } else {
            // ?????????????????????????????????__test_xxx
            if (EagleeyeHelper.isTestMode()) {
                MysqlSqlVisitor sqlVisitor = new MysqlSqlVisitor(new StringBuilder(groupHint), null);
                statement.accept(sqlVisitor);
                sqls.put(_DIRECT, sqlVisitor.getSql());
            } else {
                // ????????????????????????????????????sql???????????????????????????
                sqls.put(_DIRECT, sqlAnalysisResult.getSql());
            }
        }
        return sqls;
    }

    /**
     * ???????????????????????????????????????
     */
    private List<IDataNodeExecutor> buildRulePlain(SqlType sqlType, List<TargetDB> targetDBs, Map<String, String> sqls,
                                                   int index) {
        List<IDataNodeExecutor> subs = new ArrayList<IDataNodeExecutor>();
        for (TargetDB target : targetDBs) {
            for (String table : target.getTableNames()) {
                subs.add(buildOneDirectPlan(sqlType, target.getDbIndex(), sqls.get(table), index));
            }
        }

        return subs;
    }

    /**
     * ??????????????????????????????????????????
     */
    private List<IDataNodeExecutor> buildDirectPlan(SqlType sqlType, String dbId, Map<String, String> sqls, int index) {
        List<IDataNodeExecutor> plans = new ArrayList<IDataNodeExecutor>();
        for (String sql : sqls.values()) {
            String[] dbIds = StringUtils.split(dbId, ',');
            for (String id : dbIds) {
                plans.add(buildOneDirectPlan(sqlType, id, sql, index));
            }
        }

        return plans;
    }

    private IDataNodeExecutor buildOneDirectPlan(SqlType sqlType, String dbId, String sql, int index) {
        IDataNodeExecutor executor = null;
        switch (sqlType) {
            case UPDATE:
                executor = ASTNodeFactory.getInstance().createUpdate();
                break;
            case DELETE:
                executor = ASTNodeFactory.getInstance().createDelete();
                break;
            case INSERT:
                executor = ASTNodeFactory.getInstance().createInsert();
                break;
            case REPLACE:
                executor = ASTNodeFactory.getInstance().createReplace();
                break;
            case SELECT:
                executor = ASTNodeFactory.getInstance().createQuery();
                break;
            default:
                if (SqlTypeParser.isQuerySqlType(sqlType)) {
                    executor = ASTNodeFactory.getInstance().createQuery();
                } else {
                    executor = ASTNodeFactory.getInstance().createUpdate();
                }
                break;
        }

        if (executor != null) {
            executor.setSql(sql);
            executor.executeOn(dbId);
        }

        if (index > -1 && executor instanceof IPut) {
            // ??????batch index
            ((IPut) executor).getBatchIndexs().add(index);
        }

        return executor;
    }

    private ASTNode createMergeForJoin(ASTNode dne, Map<String, Object> extraCmd) {
        if (dne instanceof MergeNode) {
            for (ASTNode sub : ((MergeNode) dne).getChildren()) {
                this.createMergeForJoin(sub, extraCmd);
            }
        }

        if (dne instanceof JoinNode) {
            this.createMergeForJoin(((JoinNode) dne).getLeftNode(), extraCmd);
            this.createMergeForJoin(((JoinNode) dne).getRightNode(), extraCmd);
            // ?????????????????????
            if (((JoinNode) dne).getRightNode() instanceof QueryNode) {
                QueryNode right = (QueryNode) ((JoinNode) dne).getRightNode();
                if (right.getDataNode() != null) {
                    // right???join????????????????????????????????????Merge??????mget
                    if (!right.getDataNode().equals(dne.getDataNode())) {
                        MergeNode merge = new MergeNode();
                        merge.merge(right);
                        merge.setSharded(false);
                        merge.executeOn(right.getDataNode());
                        merge.build();
                        ((JoinNode) dne).setRightNode(merge);
                    }
                }
            }
        }

        if (dne instanceof QueryNode) {
            if (((QueryNode) dne).getChild() != null) {
                this.createMergeForJoin(((QueryNode) dne).getChild(), extraCmd);
            }
        }

        return dne;
    }

    public void setCacheSize(long cacheSize) {
        this.cacheSize = cacheSize;
    }

    public void setExpireTime(long expireTime) {
        this.expireTime = expireTime;
    }

    public void setSqlParseManager(SqlParseManager sqlParseManager) {
        this.sqlParseManager = sqlParseManager;
    }

    public SqlParseManager getSqlParseManager() {
        return sqlParseManager;
    }

}
