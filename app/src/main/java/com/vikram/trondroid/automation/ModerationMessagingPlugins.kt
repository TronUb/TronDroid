package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi

/**
 * Native TDLib implementation of the upstream Admin plugin.
 *
 * All moderation actions are checked against the authenticated account's live
 * chat-member status before a request is sent. Targets can be supplied as a
 * reply, numeric user ID, or Telegram username.
 */
class AdminAutomationPlugin : AutomationPlugin {
    override val id = "userbot:admin"

    override val commands = listOf(
        AutomationCommand("ban", "Ban a replied user or ID/username, optionally for a duration (10s, 5m, 2h, 3d).", "ban <reply or user> [duration]"),
        AutomationCommand("unban", "Remove a user's ban from this chat.", "unban <reply or user>"),
        AutomationCommand("banall", "Ban all regular members after an explicit confirmation.", "banall confirm"),
        AutomationCommand("kick", "Remove a member from this chat without a permanent ban.", "kick <reply or user>"),
        AutomationCommand("mute", "Restrict a member from sending messages, optionally for a duration.", "mute <reply or user> [duration]"),
        AutomationCommand("unmute", "Restore the default member permissions for a restricted user.", "unmute <reply or user>"),
        AutomationCommand("promote", "Promote a member with a safe set of administrator permissions.", "promote <reply or user>"),
        AutomationCommand("demote", "Remove administrator permissions from a member.", "demote <reply or user>"),
        AutomationCommand("pin", "Pin a replied message. Add 'loud' to notify members.", "pin (reply required) [loud]"),
        AutomationCommand("unpin", "Unpin a replied message, or use 'all' to clear all pins.", "unpin (reply required) | unpin all")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val command = context.command.lowercase()
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)

        if (command == "pin" || command == "unpin") {
            handlePin(context, gateway, replied)
            return
        }

        val needsBanRight = command in setOf("ban", "unban", "banall", "kick", "mute", "unmute")
        val requiredRight = if (command in setOf("promote", "demote")) "promote" else if (command == "pin") "pin" else "restrict"
        val targetToken = context.arguments.firstOrNull()
        val targetId = (replied?.senderId as? TdApi.MessageSenderUser)?.userId

        if (command == "banall") {
            if (context.arguments.firstOrNull()?.equals("confirm", true) != true) {
                context.reply("This will ban every regular member in this supergroup (up to 200 members per Telegram query). Run .banall confirm to proceed.")
                return
            }
            requireAdmin(gateway, context.message.chatId, "restrict") { allowed, reason ->
                if (!allowed) {
                    context.reply(reason ?: "You need the ban/restrict members permission.")
                    return@requireAdmin
                }
                gateway.send(TdApi.GetChat(context.message.chatId)) { chatResult ->
                    val chat = chatResult as? TdApi.Chat
                    val type = chat?.type as? TdApi.ChatTypeSupergroup
                    if (type == null) {
                        context.reply("Bulk banning currently supports supergroups only. No members were changed.")
                        return@send
                    }
                    banAllMembers(context, gateway, type.supergroupId)
                }
            }
            return
        }

        if (targetId == null && targetToken.isNullOrBlank()) {
            context.reply("Reply to a user's message or provide a numeric user ID / @username.")
            return
        }

        resolveTarget(gateway, targetToken, targetId) { resolvedId, resolveError ->
            if (resolvedId == null) {
                context.reply(resolveError ?: "Unable to resolve that Telegram user.")
                return@resolveTarget
            }
            gateway.currentUserId()?.let { selfId ->
                if (resolvedId == selfId) {
                    context.reply("You cannot apply this action to your own account.")
                    return@resolveTarget
                }
            }
            requireAdmin(gateway, context.message.chatId, requiredRight) { allowed, reason ->
                if (!allowed) {
                    context.reply(reason ?: "You do not have the required administrator permission.")
                    return@requireAdmin
                }
                gateway.send(TdApi.GetChatMember(context.message.chatId, TdApi.MessageSenderUser(resolvedId))) { memberResult ->
                    val member = memberResult as? TdApi.ChatMember
                    if (member == null && command !in setOf("unban")) {
                        context.reply((memberResult as? TdApi.Error)?.let { "Unable to inspect target: ${it.message}" }
                            ?: "The target is not a member of this chat.")
                        return@send
                    }
                    if (member != null && (member.status is TdApi.ChatMemberStatusCreator ||
                            member.status is TdApi.ChatMemberStatusAdministrator)) {
                        if (command !in setOf("unban")) {
                            context.reply("This action cannot be applied to the chat owner or an administrator.")
                            return@send
                        }
                    }
                    performModeration(context, gateway, resolvedId, command, member)
                }
            }
        }
    }

    private fun resolveTarget(
        gateway: com.vikram.trondroid.telegram.TelegramClientGateway,
        token: String?,
        repliedId: Long?,
        callback: (Long?, String?) -> Unit
    ) {
        if (repliedId != null) return callback(repliedId, null)
        val value = token.orEmpty().trim().removePrefix("@")
        val numeric = value.toLongOrNull()
        if (numeric != null) {
            if (numeric > 0L) callback(numeric, null) else callback(null, "User IDs must be positive.")
            return
        }
        if (!value.matches(Regex("[A-Za-z][A-Za-z0-9_]{3,31}"))) {
            callback(null, "Enter a valid numeric user ID or Telegram username.")
            return
        }
        gateway.send(TdApi.SearchPublicChat(value)) { result ->
            val chat = result as? TdApi.Chat
            val privateType = chat?.type as? TdApi.ChatTypePrivate
            if (privateType != null) callback(privateType.userId, null)
            else callback(null, (result as? TdApi.Error)?.let { "Username lookup failed: ${it.message}" }
                ?: "That username did not resolve to a user account.")
        }
    }

    private fun requireAdmin(
        gateway: com.vikram.trondroid.telegram.TelegramClientGateway,
        chatId: Long,
        right: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val selfId = gateway.currentUserId()
        if (selfId == null) {
            callback(false, "Unable to identify the connected Telegram account.")
            return
        }
        gateway.send(TdApi.GetChatMember(chatId, TdApi.MessageSenderUser(selfId))) { result ->
            val member = result as? TdApi.ChatMember
            when (val status = member?.status) {
                is TdApi.ChatMemberStatusCreator -> callback(true, null)
                is TdApi.ChatMemberStatusAdministrator -> {
                    val permitted = when (right) {
                        "promote" -> status.rights.canPromoteMembers
                        "pin" -> status.rights.canPinMessages
                        else -> status.rights.canRestrictMembers
                    }
                    callback(permitted, if (permitted) null else "Your administrator account lacks the required '$right' permission.")
                }
                else -> callback(false, (result as? TdApi.Error)?.let { "Unable to verify admin rights: ${it.message}" }
                    ?: "You must be an administrator of this chat to use this command.")
            }
        }
    }

    private fun performModeration(
        context: AutomationContext,
        gateway: com.vikram.trondroid.telegram.TelegramClientGateway,
        userId: Long,
        command: String,
        member: TdApi.ChatMember?
    ) {
        val chatId = context.message.chatId
        val hasReplyTarget = context.message.tdMessage?.let(gateway::repliedMessage) != null
        val durationToken = if (hasReplyTarget) context.arguments.firstOrNull() else context.arguments.getOrNull(1)
        val duration = durationToken?.let(::parseDuration)
        if (durationToken != null && duration == null && command in setOf("ban", "mute")) {
            context.reply("Invalid duration. Use seconds, minutes, hours or days, e.g. 30s, 15m, 2h, 3d.")
            return
        }
        val until = duration?.let { (System.currentTimeMillis() / 1000L + it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: 0
        val sender = TdApi.MessageSenderUser(userId)
        val request: TdApi.Function<*> = when (command) {
            "ban" -> TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusBanned(until))
            "unban" -> TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusLeft())
            "kick" -> TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusBanned(
                (System.currentTimeMillis() / 1000L + 60L).toInt()
            ))
            "mute" -> {
                val permissions = TdApi.ChatPermissions(
                    false, false, false, false, false, false, false, false,
                    false, false, false, false, false, false, false
                )
                TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusRestricted(true, until, permissions))
            }
            "unmute" -> TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusMember())
            "promote" -> {
                val rights = TdApi.ChatAdministratorRights().apply {
                    canManageChat = true
                    canDeleteMessages = true
                    canRestrictMembers = true
                    canInviteUsers = true
                    canPinMessages = true
                }
                TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusAdministrator(false, rights))
            }
            "demote" -> TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusMember())
            else -> return context.reply("Unknown admin command.")
        }
        gateway.send(request) { result ->
            if (result is TdApi.Error) {
                context.reply("Telegram rejected the action: ${result.message}")
                return@send
            }
            if (command == "kick") {
                gateway.send(TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusLeft())) { leaveResult ->
                    context.reply(if (leaveResult is TdApi.Error) {
                        "Member was temporarily banned, but could not be removed from the ban list: ${leaveResult.message}"
                    } else "Member kicked from this chat.")
                }
            } else {
                val label = when (command) {
                    "ban" -> if (duration != null) "Member banned for ${durationToken}." else "Member permanently banned."
                    "unban" -> "Member unbanned."
                    "mute" -> if (duration != null) "Member muted for ${durationToken}." else "Member muted indefinitely."
                    "unmute" -> "Member unmuted."
                    "promote" -> "Member promoted with moderation, invite, pin and message-management rights."
                    "demote" -> "Administrator rights removed."
                    else -> "Action completed."
                }
                context.reply(label)
            }
        }
    }

    private fun handlePin(
        context: AutomationContext,
        gateway: com.vikram.trondroid.telegram.TelegramClientGateway,
        replied: TdApi.Message?
    ) {
        val command = context.command
        if (command == "pin" && replied == null) {
            context.reply("Reply to a message with .pin [loud].")
            return
        }
        if (command == "unpin" && replied == null &&
            context.arguments.firstOrNull()?.equals("all", true) != true
        ) {
            context.reply("Reply to a message, or use .unpin all.")
            return
        }
        requireAdmin(gateway, context.message.chatId, "pin") { allowed, reason ->
            if (!allowed) {
                context.reply(reason ?: "You lack permission to pin messages.")
                return@requireAdmin
            }
            val request: TdApi.Function<*> = when {
                command == "pin" -> TdApi.PinChatMessage(
                    context.message.chatId, replied!!.id,
                    context.arguments.firstOrNull()?.equals("loud", true) != true, false
                )
                replied != null -> TdApi.UnpinChatMessage(context.message.chatId, replied.id)
                else -> TdApi.UnpinAllChatMessages(context.message.chatId)
            }
            gateway.send(request) { result ->
                context.reply(if (result is TdApi.Error) "Telegram error: ${result.message}"
                    else if (command == "pin") "Message pinned." else "Pinned messages cleared.")
            }
        }
    }

    private fun banAllMembers(
        context: AutomationContext,
        gateway: com.vikram.trondroid.telegram.TelegramClientGateway,
        supergroupId: Long
    ) {
        gateway.send(TdApi.GetSupergroupMembers(supergroupId, TdApi.SupergroupMembersFilterRecent(), 0, 200)) { result ->
            val page = result as? TdApi.ChatMembers
            if (page == null) {
                context.reply((result as? TdApi.Error)?.let { "Unable to load members: ${it.message}" }
                    ?: "Telegram did not return the member list.")
                return@send
            }
            val candidates = page.members.mapNotNull { member ->
                val user = member.memberId as? TdApi.MessageSenderUser ?: return@mapNotNull null
                if (member.status is TdApi.ChatMemberStatusMember) user.userId else null
            }.distinct().filter { it != gateway.currentUserId() }
            if (candidates.isEmpty()) {
                context.reply("No regular members were found to ban.")
                return@send
            }
            context.reply("Bulk ban started for ${candidates.size} regular members. Administrators and the connected account are excluded.")
            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            val banned = java.util.concurrent.atomic.AtomicInteger(0)
            val failed = java.util.concurrent.atomic.AtomicInteger(0)
            candidates.forEach { userId ->
                gateway.send(TdApi.SetChatMemberStatus(
                    context.message.chatId,
                    TdApi.MessageSenderUser(userId),
                    TdApi.ChatMemberStatusBanned(0)
                )) { response ->
                    if (response is TdApi.Error) failed.incrementAndGet() else banned.incrementAndGet()
                    if (completed.incrementAndGet() == candidates.size) {
                        context.reply("Bulk ban finished. Banned: ${banned.get()}, failed: ${failed.get()}. Telegram returned ${page.members.size} members in this request.")
                    }
                }
            }
        }
    }

    private fun parseDuration(value: String): Long? {
        val match = Regex("^(\\d+)([smhd])$", RegexOption.IGNORE_CASE).matchEntire(value) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        if (amount <= 0L) return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "s" -> 1L
            "m" -> 60L
            "h" -> 3600L
            "d" -> 86400L
            else -> return null
        }
        return runCatching { Math.multiplyExact(amount, multiplier) }.getOrNull()
            ?.takeIf { it <= 366L * 86400L }
    }
}

class BroadcastAutomationPlugin : AutomationPlugin {
    override val id = "userbot:broadcast"
    override val commands = listOf(
        AutomationCommand("broadcast", "Broadcast text to loaded main-dialog chats (up to 1000).", "broadcast <text>")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val text = context.arguments.joinToString(" ").trim()
        if (text.isBlank()) return context.reply("Usage: .broadcast <message>")
        context.reply("Loading dialogs before broadcast…")
        gateway.send(TdApi.LoadChats(TdApi.ChatListMain(), 1000)) { loadResult ->
            if (loadResult is TdApi.Error) return@send context.reply("Unable to load dialogs: ${loadResult.message}")
            gateway.send(TdApi.GetChats(TdApi.ChatListMain(), 1000)) { chatsResult ->
                val chats = (chatsResult as? TdApi.Chats)?.chatIds?.distinct().orEmpty()
                if (chats.isEmpty()) return@send context.reply("No main-dialog chats are available.")
                val loaded = java.util.concurrent.ConcurrentLinkedQueue<TdApi.Chat>()
                val loadCompleted = java.util.concurrent.atomic.AtomicInteger(0)
                chats.forEach { chatId ->
                    gateway.send(TdApi.GetChat(chatId)) { chatResult ->
                        val chat = chatResult as? TdApi.Chat
                        if (chat != null) loaded.add(chat)
                        if (loadCompleted.incrementAndGet() == chats.size) {
                            val eligible = loaded.filter { chat ->
                                when (chat.type) {
                                    is TdApi.ChatTypePrivate, is TdApi.ChatTypeBasicGroup, is TdApi.ChatTypeSupergroup -> true
                                    else -> false
                                }
                            }
                            if (eligible.isEmpty()) return@send context.reply("No eligible private or group chats were found.")
                            context.reply("Broadcast started for ${eligible.size} chats.")
                            val completed = java.util.concurrent.atomic.AtomicInteger(0)
                            val sent = java.util.concurrent.atomic.AtomicInteger(0)
                            val failed = java.util.concurrent.atomic.AtomicInteger(0)
                            eligible.forEach { chat ->
                                val input = TdApi.InputMessageText(TdApi.FormattedText(text.take(4000), emptyArray()), null, true)
                                gateway.send(TdApi.SendMessage(chat.id, null, null, null, null, input)) { result ->
                                    if (result is TdApi.Error) failed.incrementAndGet() else sent.incrementAndGet()
                                    if (completed.incrementAndGet() == eligible.size) {
                                        context.reply("Broadcast finished. Sent: ${sent.get()}, failed: ${failed.get()}.")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
