import { commands, Extension } from '@tiptap/core'
import { liftEmptyBlock } from '@tiptap/pm/commands'

export const LIST_KEYMAP_OPTIONS = {
  listTypes: [
    {
      itemName: 'list_item',
      wrapperNames: ['bullet_list', 'ordered_list'],
    },
  ],
}

export function runListEnter(props) {
  const itemType = props.state.schema.nodes.list_item
  if (!itemType) return false
  let sourceId = null
  for (let depth = props.state.selection.$from.depth; depth > 0; depth -= 1) {
    const node = props.state.selection.$from.node(depth)
    if (node.type === itemType) {
      sourceId = node.attrs.id
      break
    }
  }

  if (!commands.splitListItem(itemType)(props)) {
    return liftEmptyBlock(props.state, props.dispatch)
  }

  if (sourceId) {
    const { $from } = props.tr.selection
    for (let depth = $from.depth; depth > 0; depth -= 1) {
      const node = $from.node(depth)
      if (node.type === itemType && !node.attrs.id) {
        props.tr.setNodeMarkup($from.before(depth), undefined, { ...node.attrs, id: sourceId })
        break
      }
    }
  }
  return true
}

export const ListKeyBehavior = Extension.create({
  name: 'norfoldListKeyBehavior',
  priority: 110,

  addKeyboardShortcuts() {
    return {
      Enter: () => this.editor.commands.command(runListEnter),
    }
  },
})
