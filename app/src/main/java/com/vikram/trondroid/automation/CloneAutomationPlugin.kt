package com.vikram.trondroid.automation

import android.content.SharedPreferences
import org.drinkless.tdlib.TdApi

class CloneAutomationPlugin(
    private val preferences: SharedPreferences,
    private val ownerId: () -> Long?
) : AutomationPlugin {
    override val id = "userbot:clone"
    override val commands = listOf(
        AutomationCommand("clone", "Copy a user's first name, last name and bio to your profile.", "clone <user id> (or reply)"),
        AutomationCommand("revert", "Restore the profile snapshot saved before the last clone.", "revert")
    )
    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val owner = ownerId() ?: return context.reply("Connected account ID is unavailable.")
        if (context.command == "revert") {
            if (!preferences.contains("original_first")) return context.reply("No saved profile snapshot exists.")
            val first = preferences.getString("original_first", "").orEmpty()
            val last = preferences.getString("original_last", "").orEmpty()
            val bio = preferences.getString("original_bio", "").orEmpty()
            gateway.send(TdApi.SetName(first, last)) { nameResult ->
                if (nameResult is TdApi.Error) return@send context.reply("Name restore failed: ${nameResult.message}")
                gateway.send(TdApi.SetBio(bio)) { bioResult ->
                    context.reply(if (bioResult is TdApi.Error) "Name restored, bio restore failed: ${bioResult.message}" else "Original profile name and bio restored.")
                }
            }
            return
        }
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
        val targetId = context.arguments.firstOrNull()?.toLongOrNull()
            ?: (replied?.senderId as? TdApi.MessageSenderUser)?.userId
            ?: return context.reply("Usage: .clone <user id> or reply to a user's message")
        if (targetId == owner) return context.reply("That is already your own profile.")
        context.reply("Loading profile details…")
        gateway.send(TdApi.GetUser(owner)) { ownResult ->
            val own = ownResult as? TdApi.User
                ?: return@send context.reply(if (ownResult is TdApi.Error) ownResult.message else "Unable to load your profile.")
            gateway.send(TdApi.GetUserFullInfo(owner)) { ownFullResult ->
                val ownFull = ownFullResult as? TdApi.UserFullInfo
                    ?: return@send context.reply(if (ownFullResult is TdApi.Error) ownFullResult.message else "Unable to load your profile bio.")
                gateway.send(TdApi.GetUser(targetId)) { targetResult ->
                    val target = targetResult as? TdApi.User
                        ?: return@send context.reply(if (targetResult is TdApi.Error) targetResult.message else "Target user not found.")
                    gateway.send(TdApi.GetUserFullInfo(targetId)) { targetFullResult ->
                        val targetFull = targetFullResult as? TdApi.UserFullInfo
                            ?: return@send context.reply(if (targetFullResult is TdApi.Error) targetFullResult.message else "Unable to load target bio.")
                        preferences.edit()
                            .putString("original_first", own.firstName)
                            .putString("original_last", own.lastName)
                            .putString("original_bio", ownFull.bio.text)
                            .apply()
                        gateway.send(TdApi.SetName(target.firstName.take(64), target.lastName.take(64))) { setName ->
                            if (setName is TdApi.Error) return@send context.reply("Profile name update failed: ${setName.message}")
                            gateway.send(TdApi.SetBio(targetFull.bio.text.take(140))) { setBio ->
                                context.reply(if (setBio is TdApi.Error) "Name cloned, but bio update failed: ${setBio.message}" else "Profile cloned. Use .revert to restore the saved name and bio.")
                            }
                        }
                    }
                }
            }
        }
    }
}
