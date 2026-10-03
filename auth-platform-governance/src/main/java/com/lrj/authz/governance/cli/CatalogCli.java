package com.lrj.authz.governance.cli;

import com.lrj.authz.governance.application.*;
import com.lrj.authz.governance.authentication.VerifiedLogin;
import com.lrj.authz.governance.persistence.*;
import java.io.PrintWriter;
import java.nio.file.*;

/** 受控应用清单工具，注册身份来自0600配置；HTTP用户不能使用此引导权威。 */
public final class CatalogCli {
    private CatalogCli() {}
    /** register只登记固定配置；preview/publish读取无凭据清单文件。 */
    public static void main(String[] args) {
        int code=run(args,new PrintWriter(System.out,true),new PrintWriter(System.err,true));
        if(code!=0) { System.exit(code); }
    }
    /** 命令错误只输出稳定代码，不回显配置内容或底层SQL。 */
    public static int run(String[] args,PrintWriter output,PrintWriter error) {
        try {
            if(args.length!=3) { throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT); }
            var config=GovernanceConfigurationFile.read(args[1]);
            try(var runtime=GovernanceRuntime.open(GovernanceDatabase.from(config),false)) {
                switch(args[0]) {
                    case "register" -> {
                        if(!"configured".equals(args[2])) { throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT); }
                        var app=runtime.catalog().register(config.getProperty("catalog.application"),config.getProperty("catalog.owner-principal"),
                                config.getProperty("catalog.entry-origin"),config.getProperty("catalog.operator"),config.getProperty("catalog.command"));
                        output.println("REGISTERED " + app.applicationId());
                    }
                    case "publish-source", "check-source" -> {
                        var login=new VerifiedLogin(config.getProperty("catalog.owner-issuer"),config.getProperty("catalog.owner-subject"));
                        var path=Path.of(args[2]); if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
                        try(var input=Files.newInputStream(path)) {
                            var candidate=CatalogPublication.read(input);
                            if("check-source".equals(args[0])) {
                                if(!candidate.manifest().application().equals(config.getProperty("catalog.application")))throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
                                output.println(com.lrj.authz.governance.web.GovernanceWeb.body(runtime.catalog().drift(login,candidate,CatalogDrift.from(config))));
                            } else output.println(com.lrj.authz.governance.web.GovernanceWeb.body(runtime.catalog().publishSource(login,candidate,config.getProperty("catalog.command"))));
                        }
                    }
                    case "preview", "publish" -> {
                        var login=new VerifiedLogin(config.getProperty("catalog.owner-issuer"),config.getProperty("catalog.owner-subject"));
                        var path=Path.of(args[2]);
                        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) { throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT); }
                        try(var input=Files.newInputStream(path)) {
                            var manifest=CatalogManifest.read(input);
                            var result="preview".equals(args[0])?runtime.catalog().preview(login,manifest):runtime.catalog().publish(login,manifest,config.getProperty("catalog.command"));
                            output.println(com.lrj.authz.governance.web.GovernanceWeb.body(result));
                        }
                    }
                    default -> throw new GovernanceException(GovernanceException.Code.INVALID_ARGUMENT);
                }
            }
            output.flush();return 0;
        } catch(GovernanceException failure) { error.println(failure.code().value());return 2; }
        catch(Exception failure) { error.println(GovernanceException.Code.DEPENDENCY_UNAVAILABLE.value());return 3; }
    }
}
