package com.taobao.tddl.executor.function.subquery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.taobao.tddl.common.exception.TddlRuntimeException;
import com.taobao.tddl.common.utils.GeneralUtil;
import com.taobao.tddl.executor.common.ExecutionContext;
import com.taobao.tddl.executor.exception.ExecutorException;
import com.taobao.tddl.executor.function.ScalarFunction;
import com.taobao.tddl.executor.rowset.IRowSet;
import com.taobao.tddl.optimizer.OptimizerContext;
import com.taobao.tddl.optimizer.core.ast.QueryTreeNode;
import com.taobao.tddl.optimizer.core.datatype.DataType;
import com.taobao.tddl.optimizer.core.expression.IFunction.FunctionType;
import com.taobao.tddl.optimizer.core.expression.ISelectable;
import com.taobao.tddl.optimizer.core.plan.IQueryTree;
import com.taobao.tddl.optimizer.utils.OptimizerUtils;

public abstract class AbstractSubQueryFunction extends ScalarFunction {

    @Override
    public FunctionType getFunctionType() {
        return FunctionType.Scalar;
    }

    protected IQueryTree getQueryPlan(Object[] args) {
        if (args == null || args.length != 1) {
            throw new ExecutorException("impossible");
        }

        if (args[0] instanceof IQueryTree) {
            return (IQueryTree) args[0];
        } else {
            throw new ExecutorException("sub query is not IQueryTree");
        }
    }

    private IQueryTree getQueryPlan() {
        return this.getQueryPlan(this.function.getArgs().toArray());
    }

    @Override
    public DataType getReturnType() {
        List args = this.function.getArgs();
        List<ISelectable> returnColumns = null;
        if (GeneralUtil.isEmpty(args)) {
            throw new IllegalAccessError("impossible");
        }

        if (args.get(0) instanceof IQueryTree) {
            returnColumns = ((IQueryTree) args.get(0)).getColumns();
        } else if (args.get(0) instanceof QueryTreeNode) {
            returnColumns = ((QueryTreeNode) args.get(0)).getColumnsSelected();
        } else {
            throw new ExecutorException("subQuery is not IQueryTree or QueryTreeNode");
        }

        if (returnColumns.size() != 1) {
            throw new ExecutorException("only one column can be in sub query, sub query is: " + this.getQueryPlan());
        }

        ISelectable returnColumn = returnColumns.get(0);
        return returnColumn.getDataType();
    }

    @Override
    public Object scalarCalucate(IRowSet kvPair, ExecutionContext ec) throws TddlRuntimeException {

        IQueryTree query = this.getQueryPlan();

        if (query.isCorrelatedSubquery()) {
            QueryTreeNode ast = OptimizerUtils.convertPlanToAst(query);
            // ??????correlated column
            List<ISelectable> columnsCorrelated = ast.getColumnsCorrelated();
            Map<Long, Object> correlatedValues = new HashMap();
            // ???rowset??????????????????column????????????
            for (ISelectable column : columnsCorrelated) {
                Object value = getValueByIColumnWithException(kvPair, column);
                correlatedValues.put(column.getCorrelateOnFilterId(), value);
            }
            // ??????correlated column
            OptimizerContext.getContext().getOptimizer().assignmentSubquery(ast, correlatedValues, ec.getExtraCmds());
            // ????????????????????????
            query = (IQueryTree) OptimizerContext.getContext()
                .getOptimizer()
                .optimizePlan(ast, ec.getParams(), ec.getExtraCmds());

        }

        return this.compute(new Object[] { query }, ec);

    }

    public static Object getValueByIColumnWithException(IRowSet from_kv, ISelectable c) {

        Integer index = null;
        if (from_kv == null) return null;

        index = from_kv.getParentCursorMeta().getIndex(c.getTableName(), c.getColumnName(), c.getAlias());
        if (index == null) {
            throw new ExecutorException("subquery correlated column:" + c + "is not found in row:" + from_kv);
        }

        Object v = from_kv.getObject(index);
        return v;
    }

}
