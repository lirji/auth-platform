package com.lrj.authz.governance.persistence;

import java.sql.Connection;
import javax.sql.DataSource;

/** P2数据库会话互斥仅防误启动第二执行器，不代替P3远端写栅栏。 */
public final class ProjectionLock implements AutoCloseable {
    private static final long LOCK_ID=7421902001L;
    private final Connection connection;
    private ProjectionLock(Connection connection){this.connection=connection;}
    /** 独立连接持有会话锁，网络图写不占用业务SQL事务。 */
    public static ProjectionLock acquire(DataSource source){
        Connection c=null;
        try{c=source.getConnection();try(var query=c.prepareStatement("SELECT pg_try_advisory_lock(?)")){
            query.setLong(1,LOCK_ID);try(var rows=query.executeQuery()){if(!rows.next()||!rows.getBoolean(1))throw new IllegalStateException("投影执行器已运行");}}
            return new ProjectionLock(c);
        }catch(Exception e){if(c!=null)try{c.close();}catch(Exception ignored){}throw new IllegalStateException("无法取得投影单执行者锁");}
    }
    /** 会话锁必须显式释放，连接池归还本身不会释放PostgreSQL会话锁。 */
    @Override public void close(){
        try{try(var query=connection.prepareStatement("SELECT pg_advisory_unlock(?)")){query.setLong(1,LOCK_ID);query.execute();}}
        catch(Exception e){try{connection.abort(Runnable::run);}catch(Exception ignored){}throw new IllegalStateException("投影会话锁释放失败");}
        finally{try{connection.close();}catch(Exception ignored){}}
    }
}
