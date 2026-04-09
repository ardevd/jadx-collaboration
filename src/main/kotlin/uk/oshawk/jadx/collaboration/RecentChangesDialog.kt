package uk.oshawk.jadx.collaboration

import java.awt.BorderLayout
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class RecentChangesDialog(
    parent: JFrame, 
    repository: LocalRepository,
    private val onDoubleClick: (RepositoryItem) -> Unit
) : JDialog(parent, "Recent Changes", false) {
    init {
        size = Dimension(800, 600)
        defaultCloseOperation = DISPOSE_ON_CLOSE
        setLocationRelativeTo(parent)

        val columns = arrayOf("Time", "User", "Type", "Location", "New Value")
        val model = object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        // Combine renames and comments, ignoring those without a timestamp
        val items = mutableListOf<RepositoryItem>()
        items.addAll(repository.renames.filter { it.timestamp != null })
        items.addAll(repository.comments.filter { it.timestamp != null })

        // Sort descending by timestamp
        items.sortByDescending { it.timestamp }

        for (item in items) {
            val timeStr = item.timestamp?.let { dateFormat.format(Date(it)) } ?: "Unknown"
            val userStr = item.userUuid?.let { repository.users[it] } ?: "Unknown"
            
            val typeStr = when (item) {
                is RepositoryRename -> "Rename"
                is RepositoryComment -> "Comment"
                else -> "Unknown"
            }
            
            val locStr = "${item.identifier.nodeRef.declaringClass} ${item.identifier.nodeRef.shortId ?: ""}".trim()
            
            val valStr = when (item) {
                is RepositoryRename -> item.newName ?: "(deleted)"
                is RepositoryComment -> item.comment ?: "(deleted)"
                else -> ""
            }
            
            model.addRow(arrayOf(timeStr, userStr, typeStr, locStr, valStr))
        }

        val table = JTable(model)
        table.autoCreateRowSorter = true
        
        // Default sort by time descending
        val sorter = table.rowSorter as TableRowSorter<*>
        sorter.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.DESCENDING))

        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow != -1) {
                    val modelRow = table.convertRowIndexToModel(table.selectedRow)
                    if (modelRow >= 0 && modelRow < items.size) {
                        onDoubleClick(items[modelRow])
                    }
                }
            }
        })

        add(JScrollPane(table), BorderLayout.CENTER)
        
        val closeButton = JButton("Close")
        closeButton.addActionListener { dispose() }
        val buttonPanel = JPanel()
        buttonPanel.add(closeButton)
        add(buttonPanel, BorderLayout.SOUTH)
    }
}
