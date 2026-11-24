package com.finalexam.to_do_list.model.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.finalexam.to_do_list.model.local.entity.SubtaskEntity
import com.finalexam.to_do_list.model.local.entity.SyncQueueEntity
import com.finalexam.to_do_list.model.local.entity.TaskEntity
import com.finalexam.to_do_list.model.local.relation.HomeTask

@Dao
interface TaskDao {
    @Query("""SELECT t.id,t.title,COALESCE(t.description,'') AS description,
        t.category_id AS categoryId,c.name AS category,t.due_at AS dueAt,t.priority,t.is_done AS done,
        t.completed_at AS completedAt,t.created_at AS createdAt,t.client_updated_at AS updatedAt,
        t.repeat_type AS repeatType,r.remind_at AS remindAt,r.is_enabled AS reminderEnabled,
        t.revision AS revision, m.role AS memberRole, :uid AS workspaceUid, '' AS syncLabel,
        EXISTS(SELECT 1 FROM category_members other
            WHERE other.category_id=c.id AND other.user_uid<>m.user_uid AND other.status='ACTIVE') AS isCategoryShared,
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
    @Query("SELECT id FROM categories WHERE owner_uid=:uid AND name_key=:key AND deleted_at IS NULL")
    fun categoryId(uid: String, key: String): String?
    @Query("SELECT role FROM category_members WHERE category_id=:categoryId AND user_uid=:uid AND status='ACTIVE'")
    fun role(categoryId: String, uid: String): String?
    @Query("SELECT * FROM subtasks WHERE task_id=:taskId AND deleted_at IS NULL ORDER BY sort_order, client_updated_at")
    fun subtasks(taskId: String): List<SubtaskEntity>
    @Query("SELECT * FROM subtasks WHERE id=:id AND deleted_at IS NULL") fun findSubtask(id: String): SubtaskEntity?
    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM subtasks WHERE task_id=:taskId AND deleted_at IS NULL")
    fun nextSubtaskOrder(taskId: String): Int
    @Insert fun enqueueSyncOperation(operation: SyncQueueEntity)
}
