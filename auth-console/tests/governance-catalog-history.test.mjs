import { test } from 'node:test'
import assert from 'node:assert/strict'
import { validateCatalogHistory, validateCatalogReleaseDetail } from '../src/governance/catalogHistory.ts'
const row=version=>({application:'commerce',version,source:null})
test('real empty and unknown-source history remain distinct',()=>{
 assert.equal(validateCatalogHistory({items:[],next_before_version:null},'commerce').items.length,0)
 assert.equal(validateCatalogHistory({items:[row(2),row(1)],next_before_version:null},'commerce').items[0].source,null)
})
test('wrong app, missing cursor, reversed, repeated or stale page is rejected',()=>{
 for(const value of [{items:[],next_before_version:1},{items:[]},{items:[{...row(1),application:'other'}],next_before_version:null},
 {items:[row(1),row(2)],next_before_version:null},{items:[row(1),row(1)],next_before_version:null},{items:[row(4)],next_before_version:null},
 {items:[row(2)],next_before_version:1}])assert.throws(()=>validateCatalogHistory(value,'commerce',4))
})
test('fixed detail rejects a newer current manifest',()=>{
 const value={release:row(1),manifest:{application:'commerce',manifest_version:1}}
 assert.equal(validateCatalogReleaseDetail(value,'commerce',1).release.version,1)
 assert.throws(()=>validateCatalogReleaseDetail({...value,manifest:{...value.manifest,manifest_version:2}},'commerce',1))
})
