package com.app.market.domain.repository

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.MarketProfile
import com.app.market.domain.model.profile.OppoRequestContext
import com.app.market.domain.model.profile.ProfileSource
import com.app.market.domain.model.profile.ProfileTemplate
import com.app.market.domain.model.profile.SamsungRequestContext

/** Persistent device profile used by market requests. */
interface ProfileRepository {
    suspend fun load(appSource: AppSource = AppSource.XIAOMI): MarketProfile
    suspend fun save(profile: MarketProfile)
    suspend fun save(profile: MarketProfile, overridden: Set<String>, appSource: AppSource = AppSource.XIAOMI)
    suspend fun currentSource(appSource: AppSource = AppSource.XIAOMI): ProfileSource
    suspend fun setSource(source: ProfileSource, appSource: AppSource = AppSource.XIAOMI)
    suspend fun oppoRequestContext(): OppoRequestContext
    suspend fun saveOppoRequestContext(context: OppoRequestContext)
    suspend fun samsungRequestContext(): SamsungRequestContext
    suspend fun saveSamsungRequestContext(context: SamsungRequestContext)
    suspend fun currentTemplateName(appSource: AppSource = AppSource.XIAOMI): String?
    suspend fun setCurrentTemplateName(name: String?, appSource: AppSource = AppSource.XIAOMI)
    suspend fun templates(): List<ProfileTemplate>
    suspend fun saveTemplate(name: String, profile: MarketProfile, appSource: AppSource = AppSource.XIAOMI)
    suspend fun applyTemplate(name: String, appSource: AppSource = AppSource.XIAOMI): Boolean
    suspend fun deleteTemplate(name: String): Boolean
    fun canUseDevice(appSource: AppSource = AppSource.XIAOMI): Boolean
    suspend fun resetField(name: String, appSource: AppSource = AppSource.XIAOMI)
    suspend fun resetAll(appSource: AppSource = AppSource.XIAOMI)
    suspend fun isOverridden(name: String, appSource: AppSource = AppSource.XIAOMI): Boolean
    suspend fun syncFromServerIfDue()
}
