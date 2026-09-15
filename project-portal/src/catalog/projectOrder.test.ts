import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { applySavedOrder, moveVisible } from './projectOrder.ts'

describe('project order', () => {
  it('把已保存 id 提前，未知 id 忽略，新增项目跟在后面', () => {
    const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }]
    assert.deepEqual(applySavedOrder(items, ['c', 'missing', 'a']).map((item) => item.id), ['c', 'a', 'b'])
  })

  it('只重排当前可见项，隐藏项相对位置不变', () => {
    const full = ['a', 'b', 'c', 'd']
    const visible = ['a', 'c', 'd']
    assert.deepEqual(moveVisible(full, visible, 0, 2), ['c', 'b', 'd', 'a'])
    assert.deepEqual(moveVisible(full, visible, 2, 0), ['d', 'b', 'a', 'c'])
  })

  it('越界或同位置不改顺序', () => {
    assert.deepEqual(moveVisible(['a', 'b'], ['a', 'b'], 0, 0), ['a', 'b'])
    assert.deepEqual(moveVisible(['a', 'b'], ['a', 'b'], 0, 9), ['a', 'b'])
  })
})
