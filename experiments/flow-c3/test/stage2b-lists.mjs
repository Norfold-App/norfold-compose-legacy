import assert from 'node:assert/strict'
import { Schema } from 'prosemirror-model'
import { roundTripCanonical } from '../src/block-document-projection.js'

const nodes = {
  doc: { content: 'block+' },
  paragraph: { content: 'inline*', group: 'block', attrs: { id: { default: null }, align: { default: 'start' }, style: { default: 'NONE' } } },
  text: { group: 'inline' },
  bullet_list: { content: 'list_item+', group: 'block', attrs: { id: { default: null }, style: { default: 'NONE' } } },
  ordered_list: { content: 'list_item+', group: 'block', attrs: { id: { default: null }, start: { default: 1 }, style: { default: 'NONE' } } },
  list_item: { content: '(paragraph | bullet_list | ordered_list)+', attrs: { id: { default: null } } },
  todo_list: { content: 'todo_item+', group: 'block', attrs: { id: { default: null }, style: { default: 'NONE' } } },
  todo_item: { content: 'inline*', group: 'block', attrs: { id: { default: null }, checked: { default: false } } },
}
const schema = new Schema({ nodes, marks: { bold: {} } })

const doc = [
  {
    v: 1, block: {
      kind: 'bullet_list', id: 'bl1', style: 'NONE', items: [
        { id: 'li1', content: [{ kind: 'text', value: 'One' }], children: [] },
        {
          id: 'li2', content: [{ kind: 'bold', children: [{ kind: 'text', value: 'Two' }] }], children: [
            { id: 'li2a', content: [{ kind: 'text', value: 'Nested' }], children: [] },
          ],
        },
      ],
    },
  },
  {
    v: 1, block: {
      kind: 'numbered_list', id: 'nl1', start: 3, style: 'NONE', items: [
        { id: 'n1', content: [{ kind: 'text', value: 'First' }], children: [] },
      ],
    },
  },
  {
    v: 1, block: {
      kind: 'todo_list', id: 'tl1', style: 'NONE', items: [
        { id: 't1', checked: true, content: [{ kind: 'text', value: 'Done' }] },
        { id: 't2', checked: false, content: [{ kind: 'text', value: 'Open' }] },
      ],
    },
  },
]

assert.deepEqual(roundTripCanonical(doc, schema), doc)

console.log('PASS stage2b lists + todos')
