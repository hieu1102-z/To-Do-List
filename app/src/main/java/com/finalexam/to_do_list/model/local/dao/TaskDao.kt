package com.finalexam.to_do_list.model.local.dao

import androidx.room.*
import com.finalexam.to_do_list.model.local.entity.TaskEntity
import com.finalexam.to_do_list.model.local.relation.HomeTask

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
}
