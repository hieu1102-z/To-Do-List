package com.finalexam.to_do_list.util

import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.repository.CategoryRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun TaskFilter.includesHomeTask(
    task: HomeTask,
    category: CategoryRef?,
    selectedDate: LocalDate?,
    today: LocalDate,
    zone: ZoneId
): Boolean {
    if (category != null && (task.categoryId != category.id || task.workspaceUid != category.workspace.uid)) return false
    if (selectedDate == null) return includes(task.done, task.dueAt, today, zone)
    return !task.done && task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } == selectedDate
}
