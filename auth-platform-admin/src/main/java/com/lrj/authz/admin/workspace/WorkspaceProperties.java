package com.lrj.authz.admin.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 授权工作区目录（authz.workspaces）。空列表时运行时回退为默认 SpiceDB 的 knowledge 工作区。 */
@ConfigurationProperties(prefix = "authz")
public class WorkspaceProperties {

    private List<Item> workspaces = new ArrayList<>();

    public List<Item> getWorkspaces() {
        return workspaces;
    }

    public void setWorkspaces(List<Item> workspaces) {
        this.workspaces = workspaces;
    }

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

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getOrganization() { return organization; }
        public void setOrganization(String organization) { this.organization = organization; }
        public List<String> getOrganizations() { return organizations; }
        public void setOrganizations(List<String> organizations) { this.organizations = organizations; }
        public String getHome() { return home; }
        public void setHome(String home) { this.home = home; }
        public List<String> getFeatures() { return features; }
        public void setFeatures(List<String> features) { this.features = features; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
}
