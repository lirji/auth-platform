import { test } from 'node:test'
import assert from 'node:assert/strict'
import { validatePublishedCatalog, roleScopeEligibility, capabilitySelectionError, menuCapabilityCodes } from '../src/governance/publishedCatalog.ts'
const p={tenant_id:'tenant',application_id:'commerce',environment:'test'}
const catalog=()=>({...p,membership_id:'member',generation:1,max_duration_seconds:600,manifest_version:1,content_hash:'a'.repeat(64),view_hash:'b'.repeat(64),
 menus:[{code:'operations',parent:null,route:null,any_of:[]},{code:'members',parent:'operations',route:'/operations/members',any_of:['commerce.member.read']}],
 capabilities:[{code:'commerce.member.read',resource_type:'commerce_member',risk_level:'HIGH',disabled:false,grantable:true},
 {code:'commerce.store.read',resource_type:'store',risk_level:'NORMAL',disabled:false,grantable:true},
 {code:'commerce.member.write',resource_type:'commerce_member',risk_level:'HIGH',disabled:true,grantable:false}],
 resource_types:[{code:'commerce_member',scope_supported:true,allowed_scope_kinds:['TENANT_ALL']},{code:'store',scope_supported:true,allowed_scope_kinds:['SPECIFIED_RESOURCES','SPECIFIED_STORES','TENANT_ALL']}]})
const role=(codes)=>({id:'role',role_code:'reader',version:1,capabilities:codes})
test('actual finite directory is accepted including zero menus',()=>{assert.equal(validatePublishedCatalog(catalog(),p).menus.length,2);assert.equal(validatePublishedCatalog({...catalog(),menus:[]},p).menus.length,0)})
test('partition mismatch, missing type metadata and unbounded response fail closed',()=>{for(const data of [{...catalog(),environment:'other'},{...catalog(),resource_types:[]},{...catalog(),capabilities:Array(201).fill(catalog().capabilities[0])}]) assert.throws(()=>validatePublishedCatalog(data,p))})
test('duplicate, cyclic, missing and undeclared menu references fail closed',()=>{for(const menus of [[catalog().menus[0],catalog().menus[0]],[{code:'a',parent:'a',route:null,any_of:[]}],[{code:'a',parent:'missing',route:null,any_of:[]}],[{code:'a',parent:null,route:'/a',any_of:['commerce.missing']}]] )assert.throws(()=>validatePublishedCatalog({...catalog(),menus},p))})
test('unknown kind and fabricated fallback fail closed',()=>{for(const r of [{code:'commerce_member',scope_supported:true,allowed_scope_kinds:['SELF']},{code:'commerce_member',scope_supported:false,allowed_scope_kinds:['TENANT_ALL']}])assert.throws(()=>validatePublishedCatalog({...catalog(),resource_types:[r,catalog().resource_types[1]]},p))})
test('whole role must have one enabled grantable resource, not a partial intersection',()=>{assert.deepEqual(roleScopeEligibility(role(['commerce.member.read']),catalog()).resourceTypes[0].allowed_scope_kinds,['TENANT_ALL']);for(const codes of [['commerce.member.read','commerce.store.read'],['commerce.member.read','commerce.member.write'],['commerce.member.read','commerce.missing'],[]])assert.ok(roleScopeEligibility(role(codes),catalog()).reason)})
test('outside ceiling and unsupported binding are explicit',()=>{const c=catalog();c.capabilities[0].grantable=false;assert.ok(roleScopeEligibility(role(['commerce.member.read']),c).reason);c.capabilities[0].grantable=true;c.resource_types[0]={code:'commerce_member',scope_supported:false,allowed_scope_kinds:[]};assert.ok(roleScopeEligibility(role(['commerce.member.read']),c).reason)})
test('copied incompatible codes need explicit correction before a single resource role',()=>{assert.equal(capabilitySelectionError(catalog(),'commerce_member',['commerce.member.read']),undefined);for(const codes of [[],['commerce.member.write'],['commerce.member.read','commerce.store.read'],['commerce.missing']])assert.ok(capabilitySelectionError(catalog(),'commerce_member',codes))})
test('parent menu browsing returns a finite context without changing selection',()=>{const c=catalog(),selected=['commerce.store.read'];assert.deepEqual([...menuCapabilityCodes(c,'operations')],['commerce.member.read']);assert.deepEqual(selected,['commerce.store.read']);assert.equal(menuCapabilityCodes(c,undefined),undefined)})

test('database menu names require bounded complete unique display pairs',()=>{
 const c=catalog();c.menus[0]={...c.menus[0],label:'会员经营',position:0};c.menus[1]={...c.menus[1],label:'会员档案',position:1};assert.equal(validatePublishedCatalog(c,p).menus[1].label,'会员档案');
 for(const override of [{label:'会员档案'}, {position:0}, {label:'<script>',position:1}, {label:' 会员档案',position:1}, {label:'会员档案',position:100}, {label:'会员档案',position:0}]) {
  const v=catalog();v.menus[0]={...v.menus[0],label:'会员经营',position:0};v.menus[1]={...v.menus[1],...override};assert.throws(()=>validatePublishedCatalog(v,p));
 }
})

test('deprecation metadata is distinct, bounded and cannot remain grantable',()=>{
 const c=catalog();c.lifecycle_owner=true;c.capabilities=c.capabilities.map(cap=>({...cap,lifecycle_state:'ACTIVE',lifecycle_version:0,lifecycle_reason:null}));assert.equal(validatePublishedCatalog(c,p).lifecycle_owner,true);
 c.capabilities[0]={...c.capabilities[0],lifecycle_state:'DEPRECATED',lifecycle_version:1,lifecycle_reason:'停止新增',grantable:false};assert.match(roleScopeEligibility(role([c.capabilities[0].code]),c).reason,/弃用/);
 for(const override of [{lifecycle_state:'RETIRED'},{lifecycle_version:-1},{lifecycle_version:0},{lifecycle_reason:''},{lifecycle_reason:'x'.repeat(501)},{grantable:true}])assert.throws(()=>validatePublishedCatalog({...c,capabilities:[{...c.capabilities[0],...override},...c.capabilities.slice(1)]},p));
 const legacy=catalog();assert.equal(validatePublishedCatalog(legacy,p).capabilities[0].lifecycle_state,undefined);assert.throws(()=>validatePublishedCatalog({...legacy,lifecycle_owner:true},p));
})
