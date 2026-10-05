package com.lrj.authz.admin.workspace.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 授权工作区目录（authz.workspaces）。空列表时运行时回退为默认 SpiceDB 的 knowledge 工作区。 */
@ConfigurationProperties(prefix = "authz")
public class WorkspaceProperties {

    private List<Item> workspaces = new ArrayList<>();

    /** 返回本实例保存的配置工作区集合，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public List<Item> getWorkspaces() {
        return workspaces;
    }

    /** 由受治理配置绑定工作区集合，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
    public void setWorkspaces(List<Item> workspaces) {
        this.workspaces = workspaces;
    }

    /** 受治理工作区配置项；入口与功能显示配置不授予组织访问或资源操作资格。 */
    public static class Item {
        private String id = "";
        private String name = "";

        /** 主 Casdoor 组织；JWT owner 命中即可进入。 */
        private String organization = "";

        /** 额外允许的组织（如知识库的 acme/globex）。 */
        private List<String> organizations = new ArrayList<>();

        /** 登录后相对落地页：grants / spaces / playground。 */
        private String home = "grants";

        private List<String> features = new ArrayList<>();

        /** 空则复用 authz.spicedb.endpoint。 */
        private String endpoint = "";

        private String token = "";

        /** 返回本实例保存的配置工作区标识，展示与导航配置不授予工作区访问资格。 */
        public String getId() {
            return id;
        }

        /** 由受治理配置绑定工作区标识，展示与导航配置不授予工作区访问资格。 */
        public void setId(String id) {
            this.id = id;
        }

        /** 返回本实例保存的配置工作区名称，展示与导航配置不授予工作区访问资格。 */
        public String getName() {
            return name;
        }

        /** 由受治理配置绑定工作区名称，展示与导航配置不授予工作区访问资格。 */
        public void setName(String name) {
            this.name = name;
        }

        /** 返回本实例保存的配置组织条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
        public String getOrganization() {
            return organization;
        }

        /** 由受治理配置绑定组织条件，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
        public void setOrganization(String organization) {
            this.organization = organization;
        }

        /** 返回本实例保存的配置组织集合，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
        public List<String> getOrganizations() {
            return organizations;
        }

        /** 由受治理配置绑定组织集合，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
        public void setOrganizations(List<String> organizations) {
            this.organizations = organizations;
        }

        /** 返回本实例保存的配置工作区入口，展示与导航配置不授予工作区访问资格。 */
        public String getHome() {
            return home;
        }

        /** 由受治理配置绑定工作区入口，展示与导航配置不授予工作区访问资格。 */
        public void setHome(String home) {
            this.home = home;
        }

        /** 返回本实例保存的配置工作区能力配置，展示与导航配置不授予工作区访问资格。 */
        public List<String> getFeatures() {
            return features;
        }

        /** 由受治理配置绑定工作区能力配置，展示与导航配置不授予工作区访问资格。 */
        public void setFeatures(List<String> features) {
            this.features = features;
        }

        /** 返回本实例保存的配置图目标地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
        public String getEndpoint() {
            return endpoint;
        }

        /** 由受治理配置绑定图目标地址，不同装配入口沿用同一配置来源，不能接受请求输入暗中覆盖。 */
        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        /** 返回本实例保存的配置服务凭据，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
        public String getToken() {
            return token;
        }

        /** 由受治理配置绑定服务凭据，保持既有凭据装配边界，调用方不能将其写入诊断日志。 */
        public void setToken(String token) {
            this.token = token;
        }
    }
}
