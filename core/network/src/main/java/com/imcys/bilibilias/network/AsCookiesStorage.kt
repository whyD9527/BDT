package com.imcys.bilibilias.network


import com.imcys.bilibilias.database.dao.BILIUserCookiesDao
import com.imcys.bilibilias.datastore.source.UsersDataSource
import com.imcys.bilibilias.network.config.CookieSendRules
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi


@OptIn(ExperimentalSerializationApi::class)
class AsCookiesStorage(
    private val usersDataSource: UsersDataSource,
    private val biliUserCookiesDao: BILIUserCookiesDao
) : CookiesStorage {
    private val cookies = mutableListOf<Cookie>()
    private val daoCoroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private suspend fun isLogin() = usersDataSource.isLogin()
    private var isInit = false
    // 添加协程的 Mutex 锁
    private val cookieMutex = Mutex()

    init {
        // 初始化当前Cookie
        runCatching {
            daoCoroutineScope.launch {
                syncDataBaseCookies()
                isInit = true
            }
        }
    }

    /**
     * 同步数据库的Cookie
     */
    suspend fun syncDataBaseCookies() {
        if (!isLogin()) {
            // 未来登录走其他的操作
        } else {
            // 登录
            val dataBaseCookies =
                biliUserCookiesDao.getBILIUserCookiesByUid(usersDataSource.getUserId())
            cookieMutex.withLock {
                dataBaseCookies.forEach {
                    cookies.removeAll { cookie -> cookie.name == it.name }
                    val cookie = Cookie(
                        name = it.name,
                        value = it.value,
                        encoding = CookieEncoding.valueOf(it.encoding.name),
                        domain = it.domain,
                        path = it.path,
                        secure = it.secure,
                        httpOnly = it.httpOnly
                    )
                    cookies.add(cookie)
                }
            }
        }
    }

    override suspend fun get(requestUrl: Url): List<Cookie> {
        if (!isInit) {
            syncDataBaseCookies()
        }
        // ⚠️ 必须按 requestUrl 过滤（2026-09-15 全量复审 A-H2）：原先这里把**全部** Cookie
        // 原样返回，而 Ktor 会把返回值直接拼进 Cookie 头、不做任何 domain 过滤 ——
        // 于是 `SESSDATA` / `bili_jct` 会被发到 `api.github.com`（更新检查）、用户粘贴的短链
        // 跳转目标、以及用户自建的第三方下载线路主机。规则见 CookieSendRules（纯函数、有单测）。
        val host = requestUrl.host
        val notUseBuvid3 = usersDataSource.users.first().notUseBuvid3
        return cookieMutex.withLock {
            cookies.filter { cookie ->
                CookieSendRules.shouldSend(host, cookie.domain) &&
                    (!notUseBuvid3 || cookie.name != "buvid3")
            }
        }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        // ⚠️ 过期判定要同时看 `Max-Age` 与 `Expires`（2026-09-15 复审 A-M3）：
        // 原来 `cookie.expires?.timestamp ?: 0` 只认 Expires —— 服务端用
        // `Set-Cookie: k=v; Max-Age=…`（无 Expires）或纯 session cookie 下发时，
        // timestamp 恒为 0（< now）→ 新 Cookie 被**静默丢弃**；反过来，
        // 服务端用过期时间删 Cookie 时旧值也不会被移除（下面按"过期即移除"处理）。
        val now = System.currentTimeMillis()
        val expiresAt = cookie.expires?.timestamp
            ?: cookie.maxAge?.let { now + it * 1000L }
        if (expiresAt != null && expiresAt < now) {
            // 已过期：把同名旧值移除（不再 return 了事）
            cookieMutex.withLock {
                cookies.removeAll { existing -> existing.name == cookie.name }
            }
            return
        }

        val mCookie = cookie.copy(domain = requestUrl.host)
        cookieMutex.withLock {
            cookies.removeAll { it -> it.name == mCookie.name }
            cookies.add(mCookie)
        }
    }


    suspend fun getCookieValue(key: String): String? {
        return cookieMutex.withLock {
            cookies.find { it.name == key }?.value
        }
    }

    suspend fun getAllCookies(): MutableList<Cookie> {
        return cookieMutex.withLock {
            cookies.toMutableList()
        }
    }

    suspend fun updateAllCookies(cookie: MutableList<Cookie>) {
        cookieMutex.withLock {
            cookies.clear()
            cookies.addAll(cookie)
        }
    }

    suspend fun clearCookies() {
        cookieMutex.withLock {
            cookies.clear()
        }
    }

    override fun close() {
    }

}
