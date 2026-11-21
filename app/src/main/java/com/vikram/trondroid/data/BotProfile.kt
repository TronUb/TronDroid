package com.vikram.trondroid.data

data class BotProfile(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val apiId: Int,
    val enabled: Boolean = true,
    val botEnabled: Boolean = false,
    val botName: String? = null,
    val botUsername: String? = null,
    val botTelegramId: Long? = null,
    val botPhotoPath: String? = null
)
