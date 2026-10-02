# 正式Commerce Owner菜单发布边界

IR-01展示实际已发布目录，不生成业务菜单。原应用清单本轮67capabilities/13resources/0menus，实际前端21导航；0menus会显示真实缺口。角色与资源选择仍来自真实能力及协议范围绑定。

后续Owner需以实际已实施导航/路由/API能力为证据，在既有真实应用注册上发布下一个manifest_version，保留已存在完整已实施能力并补准确有界menus。schema1只允许code,parent,route,any_of，没有label。最多100menus/200capabilities；父节点route=null、any_of=[]可作为浏览上下文；每个可访问路由的any_of须明确当前cap，不产生命令隐含权。Owner登录仍不是管理委派；目录读取另外要求当前HUMAN委派。

本片专有测试Owner真实HTTP发布的有界子集是member(parent context)、members `/operations/members`→commerce.member.read/create、directory `/operations/directory`→commerce.merchant.read/store.directory.read，随后v3用于unknown命令验证。仅这三项有实际当前Owner路径及已实施cap证据，不能扩大为21项已完成映射。

不能用catalog.operate替代不存在的product.read，不能把122候选全发布，不能在页面硬编码菜单/资源。CE06/07与Journey后续新增已实施能力应先合并验证，再由Owner维护一个完整权威快照，避免各域局部发布删除既有能力。正式Owner映射和真实发布交主Agent；已向Journey与CE06/07并行Owner同步。

实例标识仍由运营明确填写真实ID；ScopeFields验证有限语法与实际资源允许kind，资源Owner执行访问时的权威事实校验。不声称Grant创建时已验证门店/资源实例存在或归属。
