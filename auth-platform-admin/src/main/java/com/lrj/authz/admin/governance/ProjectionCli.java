package com.lrj.authz.admin.governance;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.AccessModels.Partition;
import com.lrj.authz.governance.persistence.*;
import com.lrj.authz.governance.web.GovernanceWeb;

/** P2显式单执行者命令，不默认启用后台任务或并发执行器。 */
public final class ProjectionCli {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ProjectionCli.class);
    private ProjectionCli(){}
    /** 一轮有界drain；配置中固定分区和独立图目标，不接受任意图写入body。 */
    public static void main(String[] args){
        try{
            if(args.length!=1)throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
            var p=GovernanceConfigurationFile.read(args[0]);
            var partition=new Partition(p.getProperty("access.tenant"),p.getProperty("access.application"),p.getProperty("access.environment"));
            try(var runtime=GovernanceRuntime.open(GovernanceDatabase.from(p),false)){
                var result=runtime.projector(GovernanceGraph.open(p)).drain(partition,50);
                LOG.info("projection result={}",GovernanceWeb.body(result));if(result.failed()>0||result.pending()>0)System.exit(2);
            }
        }catch(Exception e){LOG.error("PROJECTION_UNAVAILABLE");System.exit(3);}
    }
}
