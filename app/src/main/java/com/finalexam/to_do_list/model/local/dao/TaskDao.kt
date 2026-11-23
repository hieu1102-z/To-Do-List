package com.finalexam.to_do_list.model.local.dao

import androidx.room.*
import com.finalexam.to_do_list.model.local.entity.TaskEntity
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.local.entity.TaskReminderEntity
import com.finalexam.to_do_list.model.local.entity.SubtaskEntity
import com.finalexam.to_do_list.model.local.entity.CategoryEntity
import com.finalexam.to_do_list.model.local.entity.SyncQueueEntity
import com.finalexam.to_do_list.model.local.relation.TrashTask

@Dao
interface TaskDao {
    @Query("SELECT uid FROM users WHERE uid LIKE 'local:%'")
    fun guestUid(): String

    @Query("""SELECT t.id,t.title,COALESCE(t.description,'') AS description,
        t.category_id AS categoryId,c.name AS category,t.due_at AS dueAt,t.priority,t.is_done AS done,
        t.completed_at AS completedAt,t.created_at AS createdAt,t.client_updated_at AS updatedAt,
        t.repeat_type AS repeatType,r.remind_at AS remindAt,r.is_enabled AS reminderEnabled,
        t.revision AS revision, m.role AS memberRole, NULL AS workspaceUid, 'Local' AS syncLabel, 0 AS transferring,
        (SELECT GROUP_CONCAT(CASE WHEN u.full_name='' THEN u.email ELSE u.full_name END, ', ')
         FROM task_participants p JOIN users u ON u.uid=p.user_uid
         JOIN category_members pm ON pm.category_id=p.category_id AND pm.user_uid=p.user_uid
         WHERE p.task_id=t.id AND pm.status='ACTIVE') AS participantNames
        FROM tasks t JOIN categories c ON c.id=t.category_id
        JOIN category_members m ON m.category_id=t.category_id
        LEFT JOIN task_reminders r ON r.task_id=t.id AND r.user_uid=:uid AND r.deleted_at IS NULL
        WHERE m.user_uid=:uid AND m.status='ACTIVE' AND t.deleted_at IS NULL AND c.deleted_at IS NULL
        ORDER BY t.is_done,t.due_at IS NULL,t.due_at,t.created_at DESC""")
    fun homeTasks(uid: String): List<HomeTask>

    @Query("SELECT * FROM tasks WHERE id=:id AND deleted_at IS NULL") fun find(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE id=:id") fun findIncludingDeleted(id: String): TaskEntity?
    @Query("SELECT t.id, t.title, c.name AS category, t.deleted_at AS deletedAt, t.category_id AS categoryId, NULL AS workspaceUid, 'OWNER' AS role, 'TASK' AS entityType, NULL AS parentId, t.revision AS revision FROM tasks t JOIN categories c ON c.id=t.category_id WHERE t.deleted_at IS NOT NULL ORDER BY t.deleted_at DESC")
    fun trashTasks(): List<TrashTask>
    @Query("SELECT id, name AS title, name AS category, deleted_at AS deletedAt, id AS categoryId, NULL AS workspaceUid, 'OWNER' AS role, 'CATEGORY' AS entityType, NULL AS parentId, revision FROM categories WHERE deleted_at IS NOT NULL AND owner_uid=:uid")
    fun trashedLocalCategories(uid: String): List<TrashTask>
    @Query("UPDATE tasks SET deleted_at=NULL, client_updated_at=:updatedAt WHERE id=:id AND deleted_at IS NOT NULL")
    fun restoreTask(id: String, updatedAt: Long)
    @Query("UPDATE subtasks SET deleted_at=NULL, client_updated_at=:updatedAt WHERE task_id=:taskId AND deleted_at=:legacyDeletedAt")
    fun restoreSubtasks(taskId: String, updatedAt: Long, legacyDeletedAt: Long)
    @Query("UPDATE task_reminders SET deleted_at=NULL, is_enabled=(:enabled AND remind_at > :now), client_updated_at=:updatedAt WHERE task_id=:taskId AND deleted_at=:legacyDeletedAt")
    fun restoreReminders(taskId: String, enabled: Boolean, now: Long, updatedAt: Long, legacyDeletedAt: Long)
    @Query("DELETE FROM tasks WHERE id=:id AND deleted_at IS NOT NULL") fun permanentlyDeleteTask(id: String)
    @Query("DELETE FROM sync_queue WHERE entity_id=:entityId AND category_id=:categoryId") fun removeQueuedTaskOperations(entityId: String, categoryId: String)
    @Insert fun enqueueSyncOperation(operation: SyncQueueEntity)
    @Query("SELECT id FROM categories WHERE owner_uid=:uid AND name_key=:key AND deleted_at IS NULL")
    fun categoryId(uid: String, key: String): String?
    @Query("SELECT c.name FROM categories c JOIN category_members m ON m.category_id=c.id WHERE m.user_uid=:uid AND m.status='ACTIVE' AND c.deleted_at IS NULL ORDER BY c.created_at, c.name")
    fun categoryNames(uid: String): List<String>
    @Query("SELECT * FROM categories WHERE owner_uid=:uid AND name_key=:key AND deleted_at IS NULL") fun findCategory(uid: String, key: String): CategoryEntity?
    @Query("SELECT COUNT(*) FROM categories WHERE owner_uid=:uid AND deleted_at IS NULL") fun activeCategoryCount(uid: String): Int
    @Query("UPDATE categories SET name=:name, name_key=:nameKey, client_updated_at=:updatedAt WHERE id=:id AND deleted_at IS NULL")
    fun renameCategory(id: String, name: String, nameKey: String, updatedAt: Long)
    @Query("UPDATE categories SET deleted_at=:deletedAt, client_updated_at=:updatedAt WHERE id=:id AND deleted_at IS NULL")
    fun softDeleteCategory(id: String, deletedAt: Long, updatedAt: Long)
    @Query("SELECT * FROM tasks WHERE category_id=:categoryId AND deleted_at IS NULL") fun activeTasksInCategory(categoryId: String): List<TaskEntity>
    @Query("UPDATE tasks SET deleted_at=:deletedAt, client_updated_at=:updatedAt WHERE id=:id AND deleted_at IS NULL")
    fun softDeleteTask(id: String, deletedAt: Long, updatedAt: Long)
    @Query("SELECT role FROM category_members WHERE category_id=:categoryId AND user_uid=:uid AND status='ACTIVE'")
    fun role(categoryId: String, uid: String): String?
    @Insert fun insert(task: TaskEntity)
    @Update fun update(task: TaskEntity)
    @Query("SELECT * FROM subtasks WHERE task_id=:taskId AND deleted_at IS NULL ORDER BY sort_order, client_updated_at")
    fun subtasks(taskId: String): List<SubtaskEntity>
    @Query("SELECT * FROM subtasks WHERE id=:id AND deleted_at IS NULL") fun findSubtask(id: String): SubtaskEntity?
    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM subtasks WHERE task_id=:taskId AND deleted_at IS NULL")
    fun nextSubtaskOrder(taskId: String): Int
    @Insert fun insertSubtask(subtask: SubtaskEntity)
    @Update fun updateSubtask(subtask: SubtaskEntity)
    @Query("UPDATE subtasks SET deleted_at=:deletedAt, client_updated_at=:updatedAt WHERE id=:id AND deleted_at IS NULL")
    fun softDeleteSubtask(id: String, deletedAt: Long, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveReminder(reminder: TaskReminderEntity)

    @Query("""
    SELECT * FROM task_reminders
    WHERE user_uid = :userUid AND is_enabled = 1 AND deleted_at IS NULL
    """)
    fun getEnabledReminders(userUid: String): List<TaskReminderEntity>

    @Query("""
    SELECT * FROM task_reminders
    WHERE user_uid = :userUid AND deleted_at IS NULL
    """)
    fun getAllReminders(userUid: String): List<TaskReminderEntity>

    @Query("SELECT * FROM task_reminders WHERE task_id=:taskId AND user_uid=:userUid AND deleted_at IS NULL")
    fun findReminder(taskId: String, userUid: String): TaskReminderEntity?

    @Query("UPDATE task_reminders SET deleted_at=:deletedAt, is_enabled=0, client_updated_at=:updatedAt WHERE task_id=:taskId AND user_uid=:userUid AND deleted_at IS NULL")
    fun softDeleteReminder(taskId: String, userUid: String, deletedAt: Long, updatedAt: Long)

    @Query("""
    UPDATE task_reminders
    SET is_enabled = :enabled,
        client_updated_at = :updatedAt
    WHERE task_id = :taskId AND user_uid = :userUid AND deleted_at IS NULL
    """)
    fun updateReminderStatus(
        taskId: String,
        userUid: String,
        enabled: Boolean,
        updatedAt: Long
    )
}
