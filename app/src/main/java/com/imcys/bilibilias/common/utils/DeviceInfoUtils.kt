package com.imcys.bilibilias.common.utils

import com.imcys.bilibilias.R
import android.content.Context
import android.os.Build
import com.hjq.device.compat.DeviceBrand.*
import com.hjq.device.compat.DeviceMarketName
import com.hjq.device.compat.DeviceOs.*

object DeviceInfoUtils {


    fun getMarketingName(): String? = getPropByBrand()

    private fun getPropByBrand(): String? {
        val brand = Build.BRAND?.lowercase() ?: return null
        val key = brandToPropKey[brand] ?: "ro.product.marketname"
        return getSystemProp(key)
    }

    private val brandToPropKey = mapOf(
        "xiaomi" to "ro.product.marketname",
        "redmi" to "ro.product.marketname",
        "huawei" to "ro.config.marketing_name",
        "honor" to "ro.config.marketing_name",
        "oppo" to "ro.oppo.market.name",
        "realme" to "ro.product.realme.marketname",
        "oneplus" to "ro.product.marketname",
        "vivo" to "ro.vivo.market.name",
        "iqoo" to "ro.product.marketname",
        "samsung" to "ro.product.model",   // 三星 model 即市场名
        "meizu" to "ro.product.marketname",
        "nubia" to "ro.product.marketname",
        "zte" to "ro.product.marketname",
        "asus" to "ro.product.marketname", // ROG
        "blackshark" to "ro.product.marketname",
        "smartisan" to "ro.product.marketname"
    )

    private fun getSystemProp(key: String): String? = try {
        val value = Class.forName("android.os.SystemProperties")
            .getDeclaredMethod("get", String::class.java)
            .invoke(null, key) as? String
        if (!value.isNullOrBlank()) value else null
    } catch (_: Throwable) {
        null
    }

    fun getDeviceInfoCopyString(context: Context): String {
        val deviceInfo = getDeviceInfo(context)

        return context.getString(
            R.string.device_info_copy_template,
            deviceInfo.appVersion,
            deviceInfo.systemVersion,
            deviceInfo.model,
            deviceInfo.marketModel,
            deviceInfo.manufacturer,
            deviceInfo.brandName,
            deviceInfo.osName,
            deviceInfo.osVersionName,
        )
    }

    fun getDeviceInfo(context: Context): DeviceInfo {
        val packageManager = context.packageManager
        val packageName = context.packageName
        val packageInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        val appVersion = packageInfo?.versionName ?: context.getString(R.string.common_unknown)
        val systemVersion = Build.VERSION.RELEASE ?: context.getString(R.string.common_unknown)
        val model = Build.MODEL ?: context.getString(R.string.common_unknown)
        val marketModel = DeviceMarketName.getMarketName(context) ?: context.getString(R.string.common_unknown)
        // ⚠️ manufacturer 要用 Build.MANUFACTURER（2026-09-15 复审 L15）：
        // 原来写的是 Build.BRAND，导致"厂商/品牌"两行永远一模一样（Xiaomi/Redmi 这种会被抹平），
        // 用户复制设备信息来报障时反而误导排查。
        val manufacturer = Build.MANUFACTURER ?: context.getString(R.string.common_unknown)
        val brand = Build.BRAND ?: context.getString(R.string.common_unknown)
        val brandName = try {
            getBrandName() ?: Build.DEVICE
        } catch (_: Throwable) {
            Build.DEVICE
        }
        val osName = try {
            getOsName() ?: context.getString(R.string.common_unknown)
        } catch (_: Throwable) {
            context.getString(R.string.common_unknown)
        }
        val osVersionName = try {
            getOsVersionName() ?: context.getString(R.string.common_unknown)
        } catch (_: Throwable) {
            context.getString(R.string.common_unknown)
        }
        return DeviceInfo(
            appVersion = appVersion,
            systemVersion = systemVersion,
            model = model,
            marketModel = marketModel,
            manufacturer = manufacturer,
            brand = brand,
            brandName = brandName,
            osName = osName,
            osVersionName = osVersionName
        )
    }

}

/**
 * 设备信息
 */
data class DeviceInfo(
    val appVersion: String,
    val systemVersion: String,
    val model: String,
    val marketModel: String,
    val manufacturer: String,
    val brand: String,
    val brandName: String,
    val osName: String,
    val osVersionName: String
)

