/**
 * Copyright [2021] [chen junwen]
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaLog;
import io.mycat.beans.mysql.MySQLIsolation;
import io.mycat.newquery.NewMycatConnection;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class LocalXaSqlConnection extends BaseXaSqlConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalXaSqlConnection.class);
    volatile NewMycatConnection localSqlConnection = null;

    public LocalXaSqlConnection(MySQLIsolation isolation, Supplier<MySQLManager> mySQLManagerSupplier,
                                XaLog xaLog) {
        super(isolation, mySQLManagerSupplier, xaLog);
    }

    @Override
    public Future<Void> begin() {
        if (inTranscation) {
            LOGGER.warn("local xa transaction occur nested transaction,xid:" + getXid());
            return Future.succeededFuture();
        }
        inTranscation = true;
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> commit() {
        String curXid = xid;
        if (localSqlConnection == null && map.isEmpty()) {
            inTranscation = false;
            return (Future.succeededFuture());
        }
        if (localSqlConnection != null && map.isEmpty()) {
            return localSqlConnection.update("commit;")
                    .onSuccess(event -> inTranscation = false).mapEmpty();
        }
        if (inTranscation && localSqlConnection != null) {
            return super.commitXa((coordinatorLog) -> localSqlConnection.update(
                            "REPLACE INTO mycat.xa_log (xid) VALUES('" + curXid + "');").mapEmpty())
                    .compose((Function<Void, Future<Void>>) o -> {
                        return localSqlConnection.update("commit;").compose(unused -> {
                            return localSqlConnection.update("delete from mycat.xa_log where xid = '" + curXid + "'").mapEmpty();
                        });
                    }).mapEmpty()
                    .compose(o -> {
                        xid = null;
                        inTranscation = false;
                        NewMycatConnection localSqlConnection = this.localSqlConnection;
                        this.localSqlConnection = null;
                        return localSqlConnection.close();
                    }).mapEmpty();
        } else {
            throw new AssertionError();
        }
    }

    @Override
    public Future<NewMycatConnection> getConnection(String targetName) {
        MySQLManager mySQLManager = mySQLManager();
        if (inTranscation) {
            if (localSqlConnection == null) {
                Future<NewMycatConnection> sqlConnectionFuture = mySQLManager.getConnection(targetName);
                return sqlConnectionFuture.map(sqlConnection -> {
                    LocalXaSqlConnection.this.localSqlConnection = sqlConnection;
                    return sqlConnection;
                }).compose(sqlConnection -> sqlConnection
                        .update(getTransactionIsolation().getCmd())
                        .flatMap(unused -> sqlConnection.update("begin;").map(sqlConnection)));
            }
            if (this.localSqlConnection != null && this.localSqlConnection.getTargetName().equals(targetName)) {
                return Future.succeededFuture(localSqlConnection);
            }
            if (xid == null) {
                xid = log.nextXid();
                log.beginXa(xid);
            }
            return super.getConnection(targetName);
        }
        return mySQLManager.getConnection(targetName).map(connection -> {
            if (!map.containsKey(targetName)) {
                map.put(targetName, connection);
            } else {
                extraConnections.add(connection);
            }
            return connection;
        });
    }

    @Override
    public Future<Void> rollback() {
        if (localSqlConnection == null && map.isEmpty()) {
            inTranscation = false;
            xid = null;
            return Future.succeededFuture();
        }
        if (localSqlConnection != null && map.isEmpty()) {
            inTranscation = false;
            return localSqlConnection.update("rollback;").transform(unused -> {
                LOGGER.error("", unused.cause());
                localSqlConnection.abandonConnection();
                return Future.succeededFuture();
            }).mapEmpty();
        }
        String curXid = this.xid;
        NewMycatConnection curLocalSqlConnection = this.localSqlConnection;
        return super.rollback().compose(unused -> curLocalSqlConnection.update("rollback;").flatMap(unused1 -> {
            this.localSqlConnection = null;
            this.xid = null;
            return curLocalSqlConnection.update("delete from mycat.xa_log where xid = '" + curXid + "'");
        })).transform(u -> {
            if (u.failed()) {
                LOGGER.error("", u.cause());
                if (curLocalSqlConnection != null) {
                    curLocalSqlConnection.abandonConnection();
                }
            } else {
                if (curLocalSqlConnection != null) {
                    curLocalSqlConnection.close();
                }
            }
            this.localSqlConnection = null;
            inTranscation = false;
            return Future.succeededFuture();
        }).mapEmpty();
    }

    @Override
    public Future<Void> closeStatementState() {
        Future<Void> future = Future.succeededFuture();
        if (localSqlConnection != null) {
            future = localSqlConnection.abandonQuery();
        }
        return CompositeFuture.join(future, super.closeStatementState()
                .flatMap(event -> {
                    if (!isInTransaction()) {
                        NewMycatConnection localSqlConnection = this.localSqlConnection;
                        this.localSqlConnection = null;
                        if (localSqlConnection != null) {
                            return localSqlConnection.close();
                        } else {
                            return Future.succeededFuture();
                        }
                    } else {
                        return Future.succeededFuture();
                    }
                })).mapEmpty();
    }

    @Override
    public Future<Void> close() {
        return rollback();
    }

    @Override
    public List<NewMycatConnection> getExistedTranscationConnections() {
        if (localSqlConnection == null) {
            return super.getExistedTranscationConnections();
        }
        ArrayList<NewMycatConnection> newMycatConnections = new ArrayList<>();
        newMycatConnections.add(localSqlConnection);
        newMycatConnections.addAll(super.getExistedTranscationConnections());
        return newMycatConnections;
    }

    @Override
    public Future<Void> kill() {
        Future<Void> future = rollback();
        return future.flatMap(unused -> {
            if (localSqlConnection != null) {
                localSqlConnection.abandonConnection();
                localSqlConnection = null;
            }
            return super.kill();
        });
    }

    @Override
    public List<NewMycatConnection> getAllConnections() {
        List<NewMycatConnection> allConnections = super.getAllConnections();
        ArrayList<NewMycatConnection> resList = new ArrayList<>(allConnections.size() + 1);
        if (localSqlConnection != null) {
            resList.add(localSqlConnection);
        }
        return resList;
    }
}
