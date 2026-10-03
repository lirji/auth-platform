package com.lrj.authz.governance.cli;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.domain.CatalogPublisherModels.Target;
import com.lrj.authz.governance.persistence.*;
import java.io.PrintWriter;

/** 目标初始化是显式受控管理命令，应用启动和任何HTTP请求均不允许重新绑定。 */
public final class CatalogPublisherTargetCli {
    private CatalogPublisherTargetCli() {}
    /** 仅接收私密配置路径，目标、操作者和原因不从普通请求取得。 */
    public static void main(String[] args) {
        int code=run(args,new PrintWriter(System.out,true),new PrintWriter(System.err,true));if(code!=0)System.exit(code);
    }
    /** 原目标可幂等核对，异目标拒绝；错误只返回稳定代码。 */
    public static int run(String[] args,PrintWriter output,PrintWriter error) {
        try {
            if(args.length!=2 || !"initialize-target".equals(args[0]))throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
            var p=GovernanceConfigurationFile.read(args[1]);
            try(var runtime=GovernanceRuntime.open(GovernanceDatabase.from(p),false)) {
                var target=runtime.initializePublisherTarget(new Target(p.getProperty("publisher.target-instance-id"),p.getProperty("publisher.environment")),p.getProperty("publisher.initialize.operator"),p.getProperty("publisher.initialize.reason"));
                output.println(com.lrj.authz.governance.web.GovernanceWeb.body(target));output.flush();return 0;
            }
        }catch(GovernanceException failure){error.println(failure.code().value());return 2;}
        catch(Exception failure){error.println(GovernanceException.Code.DEPENDENCY_UNAVAILABLE.value());return 3;}
    }
}
