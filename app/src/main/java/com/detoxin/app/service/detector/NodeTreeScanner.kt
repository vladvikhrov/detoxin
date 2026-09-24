package com.detoxin.app.service.detector

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Безопасный сборщик снимков дерева нод.
 *
 * 1. Итеративный DFS с явным стеком (без риска StackOverflow) и жёстким лимитом
 *    [MAX_DEPTH] уровней и [MAX_NODES] нод — время обхода ограничено даже на тяжёлых экранах.
 * 2. Якорный поиск `findAccessibilityNodeInfosByText`: выполняется на стороне целевого
 *    приложения, не зависит от глубины и находит подписи вкладок, лежащие глубже лимита DFS.
 *
 * Правило владения: корень принадлежит вызывающему коду; каждая дочерняя нода,
 * родитель и результат поиска утилизируются здесь же в `finally`, в том числе при исключении.
 */
internal object NodeTreeScanner {

    const val MAX_DEPTH = 8
    const val MAX_NODES = 600

    private class Frame(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val parentSelected: Boolean,
        val owned: Boolean,
    )

    fun capture(root: AccessibilityNodeInfo, anchorQueries: Collection<String>): List<NodeSnapshot> {
        val result = ArrayList<NodeSnapshot>(128)
        collectTree(root, result)
        for (query in anchorQueries) {
            collectAnchors(root, query, result)
        }
        return result
    }

    private fun collectTree(root: AccessibilityNodeInfo, out: MutableList<NodeSnapshot>) {
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, depth = 0, parentSelected = false, owned = false))
        try {
            while (stack.isNotEmpty() && out.size < MAX_NODES) {
                val frame = stack.removeLast()
                try {
                    val node = frame.node
                    out += node.toSnapshot(frame.depth, frame.parentSelected)
                    // Уровни 0..MAX_DEPTH-1: ровно MAX_DEPTH уровней, включая корень.
                    if (frame.depth + 1 < MAX_DEPTH) {
                        val selected = node.isSelected
                        for (index in node.childCount - 1 downTo 0) {
                            val child = node.getChild(index) ?: continue
                            stack.addLast(Frame(child, frame.depth + 1, selected, owned = true))
                        }
                    }
                } finally {
                    if (frame.owned) frame.node.recycleCompat()
                }
            }
        } finally {
            // Ноды, оставшиеся в стеке после достижения MAX_NODES или исключения.
            while (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                if (frame.owned) frame.node.recycleCompat()
            }
        }
    }

    private fun collectAnchors(root: AccessibilityNodeInfo, query: String, out: MutableList<NodeSnapshot>) {
        val found: List<AccessibilityNodeInfo> = root.findAccessibilityNodeInfosByText(query) ?: return
        for (node in found) {
            try {
                if (out.size < MAX_NODES) {
                    out += node.toSnapshot(NodeSnapshot.ANCHOR_DEPTH, node.isParentSelected())
                }
            } finally {
                node.recycleCompat()
            }
        }
    }

    private fun AccessibilityNodeInfo.isParentSelected(): Boolean {
        val parentNode = parent ?: return false
        return try {
            parentNode.isSelected
        } finally {
            parentNode.recycleCompat()
        }
    }

    private fun AccessibilityNodeInfo.toSnapshot(depth: Int, parentSelected: Boolean) = NodeSnapshot(
        className = className?.toString(),
        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        viewIdEntry = viewIdResourceName?.substringAfter(":id/"),
        isSelected = isSelected,
        isFocused = isFocused,
        parentSelected = parentSelected,
        depth = depth,
    )
}
