package com.taobao.tddl.repo.mysql.spi;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.sql.DataSource;

import org.apache.commons.lang.StringUtils;

import com.taobao.tddl.atom.utils.LoadFileUtils;
import com.taobao.tddl.common.exception.TddlNestableRuntimeException;
import com.taobao.tddl.common.jdbc.IConnection;
import com.taobao.tddl.common.jdbc.IDataSource;
import com.taobao.tddl.common.jdbc.ParameterContext;
import com.taobao.tddl.common.jdbc.ParameterMethod;
import com.taobao.tddl.executor.common.ExecutionContext;
import com.taobao.tddl.executor.cursor.IAffectRowCursor;
import com.taobao.tddl.executor.cursor.ICursorMeta;
import com.taobao.tddl.executor.cursor.ISchematicCursor;
import com.taobao.tddl.executor.cursor.impl.AffectRowCursor;
import com.taobao.tddl.executor.exception.ExecutorException;
import com.taobao.tddl.executor.record.CloneableRecord;
import com.taobao.tddl.executor.rowset.IRowSet;
import com.taobao.tddl.executor.spi.ITable;
import com.taobao.tddl.executor.spi.ITransaction;
import com.taobao.tddl.executor.spi.ITransaction.RW;
import com.taobao.tddl.executor.utils.ExecUtils;
import com.taobao.tddl.monitor.eagleeye.EagleeyeHelper;
import com.taobao.tddl.optimizer.config.table.IndexMeta;
import com.taobao.tddl.optimizer.core.plan.IDataNodeExecutor;
import com.taobao.tddl.optimizer.core.plan.IPut;
import com.taobao.tddl.optimizer.core.plan.IPut.PUT_TYPE;
import com.taobao.tddl.optimizer.core.plan.IQueryTree;
import com.taobao.tddl.repo.mysql.common.ResultSetAutoCloseConnection;
import com.taobao.tddl.repo.mysql.common.ResultSetWrapper;
import com.taobao.tddl.repo.mysql.cursor.ResultSetCursor;
import com.taobao.tddl.repo.mysql.sqlconvertor.MysqlPlanVisitorImpl;
import com.taobao.tddl.repo.mysql.sqlconvertor.SqlAndParam;
import com.taobao.tddl.statistics.ExecuteSQLOperation;
import com.taobao.tddl.statistics.NextOperation;

import com.taobao.tddl.common.utils.logger.Logger;
import com.taobao.tddl.common.utils.logger.LoggerFactory;

/**
 * jdbc ?????????????????????????????????. ?????????????????????cursor??????????????????????????????????????????????????? ps .. fuxk java
 * ???????????????????????????????????????????????????????????? ??????????????????????????????
 * 
 * @author whisper
 */
public class My_JdbcHandler implements GeneralQueryHandler {

    private static final Logger logger           = LoggerFactory.getLogger(My_JdbcHandler.class);
    protected ITransaction      myTransaction    = null;
    protected IConnection       connection       = null;
    protected ResultSet         resultSet        = null;
    protected PreparedStatement ps               = null;
    protected ExecutionType     executionType    = null;
    protected IRowSet           current          = null;
    protected IRowSet           prev_kv          = null;
    protected ICursorMeta       cursorMeta;
    protected boolean           isStreaming      = false;
    protected String            groupName        = null;
    protected IDataSource       ds               = null;
    protected ExecutionContext  executionContext = null;
    protected boolean           initPrev         = false;
    protected IDataNodeExecutor plan;

    public enum ExecutionType {
        PUT, GET
    }

    public My_JdbcHandler(ExecutionContext executionContext){
        this.executionContext = executionContext;
    }

    @Override
    public void executeQuery(ICursorMeta meta, boolean isStreaming) throws SQLException {
        setContext(meta, isStreaming);
        SqlAndParam sqlAndParam = null;
        if (executionContext.getParams() != null) {

            // ?????????????????????batch??????
            if (executionContext.getParams().isBatch()) {
                throw new ExecutorException("batch not supported query sql");
            }
        }
        sqlAndParam = new SqlAndParam();
        if (plan.getSql() != null) {
            sqlAndParam.sql = plan.getSql();
            if (executionContext.getParams() != null) {
                sqlAndParam.param = executionContext.getParams().getCurrentParameter();
            } else {
                sqlAndParam.param = new HashMap<Integer, ParameterContext>();
            }
        } else {
            cursorMeta.setIsSureLogicalIndexEqualActualIndex(true);
            if (plan instanceof IQueryTree) {
                ((IQueryTree) plan).setTopQuery(true);
                MysqlPlanVisitorImpl visitor = new MysqlPlanVisitorImpl(plan, null, null, null, null, true);
                plan.accept(visitor);
                sqlAndParam.sql = visitor.getString();
                sqlAndParam.param = visitor.getOutPutParamMap();
            }
        }
        long startTime = System.currentTimeMillis();
        try {

            executionType = ExecutionType.GET;
            connection = myTransaction.getConnection(groupName, ds, RW.READ);

            if (logger.isDebugEnabled()) {
                logger.debug("sqlAndParam:\n" + sqlAndParam);
            }

            ps = prepareStatement(sqlAndParam.sql, connection, executionContext, false);
            if (isStreaming) {
                // ???prev????????? ????????????
                setStreamingForStatement(ps);
            }

            Map<Integer, ParameterContext> map = sqlAndParam.param;
            ParameterMethod.setParameters(ps, map);

            if (executionContext.isEnableTrace()) {
                ExecuteSQLOperation op = new ExecuteSQLOperation(this.groupName, sqlAndParam.sql);
                op.setParams(executionContext.getParams());
                executionContext.getTracer().trace(op);
            }

            ResultSet rs = new ResultSetWrapper(ps.executeQuery(), this);

            this.resultSet = rs;
        } catch (Throwable e) {
            try {
                // ????????????????????????
                close();
            } finally {
                if (e.getMessage().contains("SqlType is Not Support")) {
                    // ?????????????????????
                    ps = connection.prepareStatement("select 1");
                    this.resultSet = new ResultSetWrapper(new ResultSetAutoCloseConnection(ps.executeQuery(),
                        connection,
                        ps), this);
                } else {
                    throw new ExecutorException(e);
                }
            }
        } finally {
            executionContext.getRecorder().recordSql(sqlAndParam.sql, startTime, this.groupName);
        }
    }

    @Override
    public IAffectRowCursor executeUpdate(ExecutionContext executionContext, IPut put, ITable table, IndexMeta meta)
                                                                                                                    throws SQLException {
        SqlAndParam sqlAndParam = new SqlAndParam();
        if (put.getSql() != null) {
            sqlAndParam.sql = put.getSql();
            if (executionContext.getParams() != null) {
                sqlAndParam.param = executionContext.getParams().getCurrentParameter();
            } else {
                sqlAndParam.param = new HashMap<Integer, ParameterContext>();
            }
        } else {
            MysqlPlanVisitorImpl visitor = new MysqlPlanVisitorImpl(plan,
                executionContext.getParams() != null ? executionContext.getParams().getFirstParameter() : null,
                null,
                null,
                null,
                true);
            put.accept(visitor);

            sqlAndParam.sql = visitor.getString();
            sqlAndParam.param = visitor.getOutPutParamMap();
            sqlAndParam.newParamIndexToOld = visitor.getNewParamIndexToOldMap();
        }

        boolean isInsert = false;
        if (PUT_TYPE.INSERT.equals(put.getPutType())) {
            isInsert = true;
        }

        long startTime = System.currentTimeMillis();
        boolean isBatch = executionContext.getParams() != null ? executionContext.getParams().isBatch() : false;
        try {
            // ????????????????????????????????????????????????
            connection = myTransaction.getConnection(groupName, ds, RW.WRITE);
            ps = prepareStatement(sqlAndParam.sql, connection, executionContext, isInsert);
            int affectRows = 0;
            if (isBatch) {
                for (Object o : put.getBatchIndexs()) {
                    Integer batchIndex = (Integer) o;

                    Map<Integer, ParameterContext> params = executionContext.getParams()
                        .getBatchParameters()
                        .get(batchIndex);

                    if (sqlAndParam.newParamIndexToOld != null) {
                        for (Entry<Integer, Integer> newIndexAndOld : sqlAndParam.newParamIndexToOld.entrySet()) {
                            sqlAndParam.param.get(newIndexAndOld.getKey())
                                .setValue(params.get(newIndexAndOld.getValue()).getValue());
                        }
                    } else {
                        sqlAndParam.param = params; // ??????hint+batch???,?????????????????????????????????
                    }

                    convertBigDecimal(sqlAndParam.param);
                    ParameterMethod.setParameters(ps, sqlAndParam.param);
                    ps.addBatch();
                }

                int[] nn = ps.executeBatch();

                for (int n : nn) {
                    affectRows += n;
                }
            } else {
                convertBigDecimal(sqlAndParam.param);
                ParameterMethod.setParameters(ps, sqlAndParam.param);
                if (logger.isDebugEnabled()) {
                    logger.debug("sqlAndParam:\n" + sqlAndParam);
                }

                affectRows = ps.executeUpdate();
            }
            UpdateResultWrapper urw = new UpdateResultWrapper(affectRows, this);
            executionType = ExecutionType.PUT;
            this.resultSet = urw;

            int i = resultSet.getInt(UpdateResultWrapper.AFFECT_ROW);
            IAffectRowCursor isc = new AffectRowCursor(i);
            if (isInsert) {
                ResultSet lastInsertIdResult = null;
                try {
                    lastInsertIdResult = ps.getGeneratedKeys();
                    List<Long> generatedKeys = new ArrayList<Long>();
                    while (lastInsertIdResult.next()) {
                        long id = lastInsertIdResult.getLong(1);
                        if (id != 0) {
                            executionContext.getConnection().setLastInsertId(id);
                        }

                        generatedKeys.add(id);
                    }
                    executionContext.getConnection().setGeneratedKeys(generatedKeys);
                } finally {
                    if (lastInsertIdResult != null) {
                        lastInsertIdResult.close();
                    }
                }
            }
            return isc;
        } catch (Throwable e) {
            throw new TddlNestableRuntimeException(e);
        } finally {
            executionContext.getRecorder().recordSql(sqlAndParam.sql, startTime, this.groupName);
            close();
        }
    }

    /**
     * ???ob?????????ob?????????bigdecimal????????????double
     * 
     * @param params
     */
    public void convertBigDecimal(Map<Integer, ParameterContext> params) {
        return;
    }

    @Override
    public ISchematicCursor getResultCursor() {

        if (executionType == ExecutionType.PUT) {
            throw new IllegalAccessError("impossible");
        } else if (executionType == ExecutionType.GET) {
            // get?????????????????????????????????
            ResultSet rs = resultSet;
            return new ResultSetCursor(rs, this);
        } else {
            return null;
        }

    }

    protected PreparedStatement getPs() {
        return ps;
    }

    @Override
    public void setDs(Object ds) {
        this.ds = (IDataSource) ds;
    }

    protected void setStreamingForStatement(Statement stat) throws SQLException {
        stat.setFetchSize(Integer.MIN_VALUE);
        if (logger.isDebugEnabled()) {
            logger.debug("fetchSize:\n" + stat.getFetchSize());
        }
    }

    protected void setContext(ICursorMeta meta, boolean isStreaming) {
        if (cursorMeta == null) {
            cursorMeta = meta;
        }

        if (isStreaming != this.isStreaming) {
            this.isStreaming = isStreaming;
        }
    }

    public DataSource getDs() {
        return ds;
    }

    protected boolean closeStreaming(Statement stmt, ResultSet rs) throws SQLException {
        stmt.cancel();
        return false;
    }

    @Override
    public void close() throws SQLException {
        boolean hasRealClose = false;
        try {
            /*
             * ????????????????????????????????????close?????????????????????streaming???????????? ???????????????????????????
             * http://jira.taobao.ali.com/browse/ANDOR-149
             * http://gitlab.alibaba-inc.com/andor/issues/1835
             */
            if (!isStreaming) {
                if (resultSet != null && !resultSet.isClosed()) {
                    resultSet.close();
                    resultSet = null;
                }
            } else {
                try {
                    if (resultSet != null && !resultSet.isClosed()) {
                        boolean hasNext = true;
                        try {
                            hasNext = resultSet.next();
                        } catch (SQLException e) { // ????????????????????????????????????
                            hasNext = false;
                        }

                        if (hasNext) { // ??????????????????????????????????????????????????????????????????????????????????????????
                            hasRealClose = closeStreaming(this.ps, resultSet);
                        } else {
                            resultSet.close();
                            resultSet = null;
                        }
                    }
                } catch (Throwable e) {
                    if (resultSet != null && !resultSet.isClosed()) {
                        resultSet.close();
                        resultSet = null;
                    }

                    throw new TddlNestableRuntimeException(e);
                }
            }
        } finally {
            if (!hasRealClose && ps != null) {
                ps.setFetchSize(0);
                ps.close();
                ps = null;
            } else {
                ps = null;
            }
        }

        if (this.connection != null) {
            this.executionContext.getTransaction().tryClose(this.connection, this.groupName);
        }

        executionType = null;
    }

    @Override
    public boolean skipTo(CloneableRecord key, ICursorMeta indexMeta) throws SQLException {
        checkInitedInRsNext();
        throw new RuntimeException("???????????????skip to");
    }

    @Override
    public IRowSet next() throws SQLException {
        if (ds == null) {
            throw new IllegalArgumentException("ds is null");
        }

        checkInitedInRsNext();
        prev_kv = current;
        try {
            if (resultSet.isClosed()) {
                return null;
            }
        } catch (Exception ex) {
            return null;
        }
        if (resultSet.next()) {
            current = My_Convertor.convert(resultSet, cursorMeta);

            if (executionContext.isEnableTrace()) {
                NextOperation op = new NextOperation(ExecUtils.fromIRowSetToArrayRowSet(current));
                executionContext.getTracer().trace(op);
            }
        } else {
            current = null;
        }

        return current;
    }

    protected PreparedStatement prepareStatement(String sql, IConnection conn, ExecutionContext executionContext,
                                                 boolean isInsert) throws SQLException {
        if (this.ps != null) {
            throw new IllegalStateException("?????????????????????????????????");
        }
        if (conn == null) {
            throw new IllegalStateException("should not be here");
        }

        StringBuilder append = new StringBuilder();
        String trace = buildTraceComment();
        if (trace != null) {
            append.append(trace);
        }
        if (executionContext.getGroupHint() != null) {
            // ?????????group hint???????????????hint
            append.append(executionContext.getGroupHint());
        }

        String ap = append.toString();
        if (StringUtils.isNotEmpty(ap)) {
            sql = ap + sql;
        }

        int autoGeneratedKeys = executionContext.getAutoGeneratedKeys();
        if (isInsert) {
            autoGeneratedKeys = Statement.RETURN_GENERATED_KEYS;
        }

        int[] columnIndexes = executionContext.getColumnIndexes();
        String[] columnNames = executionContext.getColumnNames();
        int resultSetType = executionContext.getResultSetType();
        int resultSetConcurrency = executionContext.getResultSetConcurrency();
        int resultSetHoldability = executionContext.getResultSetHoldability();
        // ?????????????????????txIsolation
        if (executionContext.getTxIsolation() >= 0) {
            conn.setTransactionIsolation(executionContext.getTxIsolation());
        }
        // ???????????????????????????
        if (executionContext.getEncoding() != null) {
            conn.setEncoding(executionContext.getEncoding());
        }
        // ?????????????????????sqlMode
        if (executionContext.getSqlMode() != null) {
            conn.setSqlMode(executionContext.getSqlMode());
        }
        // ????????????????????????
        if (executionContext.getSqlMetaData() != null) {
            conn.setMetaData(executionContext.getSqlMetaData());
        }

        PreparedStatement ps = null;
        if (resultSetType != -1 && resultSetConcurrency != -1 && resultSetHoldability != -1) {
            ps = conn.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        } else if (resultSetType != -1 && resultSetConcurrency != -1) {
            ps = conn.prepareStatement(sql, resultSetType, resultSetConcurrency);
        } else if (autoGeneratedKeys != -1) {
            ps = conn.prepareStatement(sql, autoGeneratedKeys);
        } else if (columnIndexes != null) {
            ps = conn.prepareStatement(sql, columnIndexes);
        } else if (columnNames != null) {
            ps = conn.prepareStatement(sql, columnNames);
        } else {
            ps = conn.prepareStatement(sql);
        }

        if (executionContext.getLocalInfileInputStream() != null) {
            LoadFileUtils.setLocalInfileInputStream(ps, executionContext.getLocalInfileInputStream());
        }
        this.ps = ps;
        return ps;
    }

    protected String buildTraceComment() {
        StringBuilder append = new StringBuilder();
        // ??????traceId,??????mysql???????????????sql???traceId?????????
        // ??????/* $traceId/$rpcId/$t/ */
        String traceId = EagleeyeHelper.getTraceId();
        String rpcId = EagleeyeHelper.getRpcId();
        boolean t = EagleeyeHelper.isTestMode();
        append.append("/* ");
        if (StringUtils.isNotEmpty(traceId)) {
            append.append(traceId);
        }
        append.append('/');
        if (StringUtils.isNotEmpty(rpcId)) {
            append.append(rpcId);
        }
        append.append('/');
        if (t) {
            append.append(1);
        }
        append.append("/ */");
        return append.toString();
    }

    @Override
    public IRowSet first() throws SQLException {
        resultSet.beforeFirst();
        resultSet.next();
        current = My_Convertor.convert(resultSet, cursorMeta);
        return current;

    }

    @Override
    public IRowSet last() throws SQLException {
        resultSet.afterLast();
        resultSet.previous();
        current = My_Convertor.convert(resultSet, cursorMeta);
        return current;

    }

    @Override
    public IRowSet getCurrent() {
        return current;
    }

    protected void checkInitedInRsNext() {
        if (!isInited()) {
            throw new IllegalArgumentException("not inited");
        }
    }

    @Override
    public boolean isInited() {
        return resultSet != null;
    }

    @Override
    public void beforeFirst() throws SQLException {
        this.close();
        this.executeQuery(cursorMeta, isStreaming);
        current = null;
    }

    @Override
    public IRowSet prev() throws SQLException {
        if (ds == null) {
            throw new IllegalArgumentException("ds is null");
        }
        if (!initPrev) {
            initPrev = true;
            return convertRowSet(resultSet.last());
        }

        checkInitedInRsNext();
        return convertRowSet(resultSet.previous());

    }

    protected IRowSet convertRowSet(boolean isOk) throws SQLException {
        prev_kv = current;
        if (isOk) {
            current = My_Convertor.convert(resultSet, cursorMeta);
        } else {
            current = null;
        }

        return current;
    }

    @Override
    public boolean isDone() {
        return true;
    }

    @Override
    public void cancel(boolean interruptedIfRunning) {
    }

    public void setMyTransaction(ITransaction myTransaction) {
        this.myTransaction = myTransaction;
    }

    @Override
    public boolean isCanceled() {
        return false;
    }

    public ITransaction getMyTransaction() {
        return myTransaction;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public void setDs(IDataSource ds) {
        this.ds = ds;
    }

    public Boolean getIsStreaming() {
        return isStreaming;
    }

    public void setIsStreaming(Boolean isStreaming) {
        this.isStreaming = isStreaming;
    }

    public ResultSet getResultSet() {
        return this.resultSet;
    }

    public void setPlan(IDataNodeExecutor plan) {
        this.plan = plan;
    }

    public IDataNodeExecutor getPlan() {
        return this.plan;
    }

    public ExecutionContext getExecutionContext() {
        return executionContext;
    }

    public void setExecutionContext(ExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

}
