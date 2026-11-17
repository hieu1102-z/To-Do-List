package com.finalexam.to_do_list.model.local.dao

import androidx.room.*
import com.finalexam.to_do_list.model.local.entity.TaskEntity
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.local.entity.TaskReminderEntity

@Dao
interface TaskDao {
    @Query("SELECT uid FROM users WHERE uid LIKE 'local:%'")
    fun guestUid(): String

    @Query("""SELECT t.id,t.title,COALESCE(t.description,'') AS description,
        t.category_id AS categoryId,c.name AS category,t.due_at AS dueAt,t.priority,t.is_done AS done,
        t.completed_at AS completedAt,t.created_at AS createdAt,t.client_updated_at AS updatedAt
        FROM tasks t JOIN categories c ON c.id=t.category_id
        JOIN category_members m ON m.category_id=t.category_id
        WHERE m.user_uid=:uid AND m.status='ACTIVE'
        ORDER BY t.is_done,t.due_at IS NULL,t.due_at,t.created_at DESC""")
    fun homeTasks(uid: String): List<HomeTask>

    @Query("SELECT * FROM tasks WHERE id=:id") fun find(id: String): TaskEntity?
    @Query("SELECT id FROM categories WHERE owner_uid=:uid AND name_key=:key")
    fun categoryId(uid: String, key: String): String?
    @Query("SELECT role FROM category_members WHERE category_id=:categoryId AND user_uid=:uid AND status='ACTIVE'")
    fun role(categoryId: String, uid: String): String?
    @Insert fun insert(task: TaskEntity)
    @Update fun update(task: TaskEntity)
    @Query("DELETE FROM tasks WHERE id=:id") fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveReminder(reminder: TaskReminderEntity)

    @Query("""
    DELETE FROM task_reminders
    WHERE task_id = :taskId AND user_uid = :userUid
    """)
    fun deleteReminder(taskId: String, userUid: String)

    @Query("""
    SELECT * FROM task_reminders
    WHERE user_uid = :userUid AND is_enabled = 1
    """)
    fun getEnabledReminders(userUid: String): List<TaskReminderEntity>

    @Query("""
    SELECT * FROM task_reminders
    WHERE user_uid = :userUid
    """)
    fun getAllReminders(userUid: String): List<TaskReminderEntity>

    @Query("""
    UPDATE task_reminders
    SET is_enabled = :enabled,
        client_updated_at = :updatedAt
    WHERE task_id = :taskId AND user_uid = :userUid
    """)
    fun updateReminderStatus(
        taskId: String,
        userUid: String,
        enabled: Boolean,
        updatedAt: Long
    )
}
