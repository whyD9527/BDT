package com.imcys.bilibilias.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.imcys.bilibilias.database.entity.BILIUsersEntity
import com.imcys.bilibilias.database.entity.LoginPlatform

@Dao
interface BILIUsersDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBILIUser(biliUsersEntity: BILIUsersEntity): Long

    @Update
    suspend fun updateBILIUser(biliUsersEntity: BILIUsersEntity)

    @Query("select * from bili_users where mid = :mid and login_platform = :loginPlatform")
    suspend fun getBILIUserByMidAndPlatform(
        mid: Long,
        loginPlatform: LoginPlatform
    ): BILIUsersEntity?

    @Query("select * from bili_users where login_platform = :loginPlatform")
    suspend fun getBILIUserByPlatform(
        loginPlatform: LoginPlatform
    ): BILIUsersEntity?

    @Query("select * from bili_users where mid in (select mid from bili_users where id = :uid )")
    suspend fun getBILIUserListByUid(
        uid: Long
    ): List<BILIUsersEntity>

    /**
     * 同一个 **B 站 mid** 下的全部平台账号（WEB / TV / MOBILE…）。
     *
     * ⚠️ 参数是 B 站 mid，**不是本地主键 id** —— 两者完全不同：
     * `getBILIUserListByUid` 里的 `:uid` 是本地 `id` 主键（子查询按 `id` 匹配），
     * 把 mid 传给它必然查不到人（2026-09-15 复审 A-M11：
     * `UserInfoRepository.getBILIUserListByMid` 就是这么传的 → 账号自检恒失败 → 静默登出）。
     */
    @Query("select * from bili_users where mid = :mid")
    suspend fun getBILIUserListByMid(
        mid: Long
    ): List<BILIUsersEntity>


    @Query("select * from bili_users where login_platform = :loginPlatform")
    suspend fun getBILIUserListByPlatform(
        loginPlatform: LoginPlatform
    ): List<BILIUsersEntity>


    @Query("select * from bili_users where id = :uid")
    suspend fun getBILIUserByUid(
        uid: Long
    ): BILIUsersEntity?

    @Query("delete from bili_users WHERE id = :userId")
    suspend fun deleteBILIUserByUid(userId: Long): Int
}