package com.taobao.tddl.executor;

import java.util.List;
import java.util.concurrent.Future;

import com.taobao.tddl.common.exception.TddlException;
import com.taobao.tddl.common.model.Group;
import com.taobao.tddl.common.model.lifecycle.AbstractLifecycle;
import com.taobao.tddl.executor.common.ExecutionContext;
import com.taobao.tddl.executor.common.ExecutorContext;
import com.taobao.tddl.executor.cursor.ISchematicCursor;
import com.taobao.tddl.executor.cursor.ResultCursor;
import com.taobao.tddl.executor.spi.ICommandHandler;
import com.taobao.tddl.executor.spi.ICommandHandlerFactory;
import com.taobao.tddl.executor.spi.IGroupExecutor;
import com.taobao.tddl.executor.spi.IRepository;
import com.taobao.tddl.optimizer.core.plan.IDataNodeExecutor;

public abstract class AbstractGroupExecutor extends AbstractLifecycle implements IGroupExecutor {

    public static final String TRANSACTION_GROUP_KEY = "GROUP_KEY";
    private final IRepository  repo;

    private Group              group;

    public AbstractGroupExecutor(IRepository repo){
        this.repo = repo;
    }

    @Override
    protected void doInit() throws TddlException {
        super.doInit();
    }

    @Override
    public ISchematicCursor execByExecPlanNode(IDataNodeExecutor qc, ExecutionContext executionContext)
                                                                                                       throws TddlException {
        executionContext.setCurrentRepository(repo);
        ISchematicCursor returnCursor = null;
        returnCursor = executeInner(qc, executionContext);
        return returnCursor;
    }

    @Override
    public Future<ISchematicCursor> execByExecPlanNodeFuture(IDataNodeExecutor qc, ExecutionContext executionContext)
                                                                                                                     throws TddlException {
        return ExecutorContext.getContext().getTopologyExecutor().execByExecPlanNodeFuture(qc, executionContext);
    }

    public ISchematicCursor executeInner(IDataNodeExecutor executor, ExecutionContext executionContext)
                                                                                                       throws TddlException {

        // ????????????????????????cursor???????????????????????????????????????????????????????????????????????????????????????????????????????????????
        // ?????????????????????????????????????????????????????????????????????????????????????????????????????????
        ICommandHandlerFactory commandExecutorFactory = this.repo.getCommandExecutorFactory();
        // ?????????????????????????????????executor,?????????????????????Handler
        ICommandHandler commandHandler = commandExecutorFactory.getCommandHandler(executor, executionContext);
        // command???????????????
        return commandHandler.handle(executor, executionContext);
    }

    @Override
    public ResultCursor commit(ExecutionContext executionContext) throws TddlException {
        ResultCursor rc = new ResultCursor(null, executionContext);

        executionContext.getTransaction().commit();
        return rc;
    }

    @Override
    public ResultCursor rollback(ExecutionContext executionContext) throws TddlException {
        ResultCursor rc = new ResultCursor(null, executionContext);

        executionContext.getTransaction().rollback();
        return rc;
    }

    @Override
    public Future<ResultCursor> commitFuture(ExecutionContext executionContext) throws TddlException {
        return ExecutorContext.getContext().getTopologyExecutor().commitFuture(executionContext);
    }

    @Override
    public Future<ResultCursor> rollbackFuture(ExecutionContext executionContext) throws TddlException {
        return ExecutorContext.getContext().getTopologyExecutor().rollbackFuture(executionContext);
    }

    @Override
    public Group getGroupInfo() {
        return this.group;
    }

    public void setGroup(Group group) {
        this.group = group;
    }

    @Override
    public String toString() {
        return "GroupExecutor [groupName=" + group.getName() + ", type=" + group.getType()
               + ", remotingExecutableObject=" + getRemotingExecutableObject() + "]";
    }

    @Override
    public Future<List<ISchematicCursor>> execByExecPlanNodesFuture(List<IDataNodeExecutor> qcs,
                                                                    ExecutionContext executionContext)
                                                                                                      throws TddlException {
        return ExecutorContext.getContext().getTopologyExecutor().execByExecPlanNodesFuture(qcs, executionContext);
    }
}
