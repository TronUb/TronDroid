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
        AutomationCommand("dban", "Delete the replied message and ban its sender.", "dban (reply required)"),
        AutomationCommand("unban", "Remove a user's ban from this chat.", "unban <reply or user>"),
        AutomationCommand("banall", "Ban all regular members after an explicit confirmation.", "banall confirm"),
        AutomationCommand("kick", "Remove a member from this chat without a permanent ban.", "kick <reply or user>"),
        AutomationCommand("mute", "Restrict a member from sending messages, optionally for a duration.", "mute <reply or user> [duration]"),
        AutomationCommand("dmute", "Delete the replied message and mute its sender.", "dmute (reply required)"),
        AutomationCommand("unmute", "Restore the default member permissions for a restricted user.", "unmute <reply or user>"),
        AutomationCommand("promote", "Promote a member with a safe set of administrator permissions.", "promote <reply or user>"),
        AutomationCommand("demote", "Remove administrator permissions from a member.", "demote <reply or user>"),
        AutomationCommand("pin", "Pin a replied message. Add 'loud' to notify members.", "pin (reply required) [loud]"),
        AutomationCommand("unpin", "Unpin a replied message, or use 'all' to clear all pins.", "unpin (reply required) | unpin all")
    )

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val command = when (context.command?.lowercase().orEmpty()) {
            "dban" -> "ban"
            "dmute" -> "mute"
            else -> context.command?.lowercase().orEmpty()
        }
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
                    canInviteUsers = true
                }
                TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusAdministrator(false, rights))
            }
            "demote" -> TdApi.SetChatMemberStatus(chatId, sender, TdApi.ChatMemberStatusMember())
            else -> return context.reply("Unknown admin command.")
        }
        val executeAction: () -> Unit = {
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
                        "promote" -> "Member promoted with invite-only administrator rights."
                        "demote" -> "Administrator rights removed."
                        else -> "Action completed."
                    }
                    context.reply(label)
                }
            }
        }
        val repliedForDelete = context.message.tdMessage?.let(gateway::repliedMessage)
        if (context.command?.startsWith("d") == true && repliedForDelete != null) {
            gateway.send(TdApi.DeleteMessages(chatId, longArrayOf(repliedForDelete.id), true)) {
                executeAction()
            }
        } else {
            executeAction()
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
        val chatId = context.message.chatId
        val userIds = linkedSetOf<Long>()

        fun loadPage(offset: Int) {
            gateway.send(TdApi.GetSupergroupMembers(
                supergroupId, TdApi.SupergroupMembersFilterRecent(), offset, 200
            )) { result ->
                val page = result as? TdApi.ChatMembers
                if (page == null) {
                    context.reply((result as? TdApi.Error)?.let { "Unable to load members: ${it.message}" }
                        ?: "Telegram did not return the member list.")
                    return@send
                }
                page.members.forEach { member ->
                    val user = member.memberId as? TdApi.MessageSenderUser
                    if (user != null && member.status is TdApi.ChatMemberStatusMember &&
                        user.userId != gateway.currentUserId()
                    ) userIds += user.userId
                }
                val nextOffset = offset + page.members.size
                if (page.members.size == 200 && nextOffset < 100_000) {
                    loadPage(nextOffset)
                } else {
                    if (userIds.isEmpty()) {
                        context.reply("No regular members were found to ban.")
                        return@send
                    }
                    context.reply("Bulk ban started for ${userIds.size} regular members. Administrators and the connected account are excluded.")
                    val targets = userIds.toList()
                    var index = 0
                    var banned = 0
                    var failed = 0
                    fun banNext() {
                        if (index >= targets.size) {
                            context.reply("Bulk ban finished. Banned: $banned, failed: $failed.")
                            return
                        }
                        val userId = targets[index++]
                        gateway.send(TdApi.SetChatMemberStatus(
                            chatId, TdApi.MessageSenderUser(userId), TdApi.ChatMemberStatusBanned(0)
                        )) { response ->
                            if (response is TdApi.Error) failed++ else banned++
                            banNext()
                        }
                    }
                    banNext()
                }
            }
        }
        loadPage(0)
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
        AutomationCommand("broadcast", "Broadcast a text announcement to private chats and groups in the main dialog list (up to 1000).", "broadcast <message>"),
        AutomationCommand("bdt", "Alias for broadcast.", "bdt <message>")
    )

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "TronDroid-Broadcast").apply { isDaemon = true }
    }

    override fun onCommand(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val commandText = context.arguments.joinToString(" ").trim()
        if (commandText.isBlank()) {
            context.reply("Usage: .broadcast <message> (or .bdt <message>)")
            return
        }
        if (commandText.length > 4096) {
            context.reply("Telegram messages are limited to 4096 characters. Shorten the announcement and try again.")
            return
        }
        if (!running.compareAndSet(false, true)) {
            context.reply("A broadcast is already running. Wait for it to finish before starting another.")
            return
        }

        context.reply("Preparing broadcast…")
        gateway.send(TdApi.LoadChats(TdApi.ChatListMain(), 1000)) { loadResult ->
            if (loadResult is TdApi.Error) {
                running.set(false)
                context.reply("Unable to load dialogs: ${loadResult.message}")
                return@send
            }
            gateway.send(TdApi.GetChats(TdApi.ChatListMain(), 1000)) { chatsResult ->
                val chatIds = (chatsResult as? TdApi.Chats)?.chatIds?.distinct().orEmpty()
                if (chatsResult is TdApi.Error || chatIds.isEmpty()) {
                    running.set(false)
                    context.reply((chatsResult as? TdApi.Error)?.let { "Unable to read dialogs: ${it.message}" }
                        ?: "No main-dialog chats are available.")
                    return@send
                }

                val targets = chatIds.filter { it != 0L }
                val userId = gateway.currentUserId()
                var index = 0
                var sentUsers = 0
                var sentGroups = 0
                var failed = 0
                var skipped = 0
                var retriedFloodWait = false

                fun finish() {
                    running.set(false)
                    context.reply(
                        "Broadcast finished. Sent to $sentUsers users and $sentGroups groups. " +
                            "Failed: $failed. Skipped: $skipped."
                    )
                }

                fun next(delayMs: Long = 300L) {
                    if (index >= targets.size) {
                        finish()
                        return
                    }
                    scheduler.schedule({
                        val chatId = targets[index++]
                        gateway.send(TdApi.GetChat(chatId)) { chatResult ->
                            val chat = chatResult as? TdApi.Chat
                            if (chat == null) {
                                failed++
                                next()
                                return@send
                            }
                            val isPrivate = chat.type is TdApi.ChatTypePrivate
                            val isGroup = chat.type is TdApi.ChatTypeBasicGroup ||
                                chat.type is TdApi.ChatTypeSupergroup
                            if (!isPrivate && !isGroup) {
                                skipped++
                                next(50L)
                                return@send
                            }
                            val privateType = chat.type as? TdApi.ChatTypePrivate
                            if (privateType != null && privateType.userId == userId) {
                                skipped++
                                next(50L)
                                return@send
                            }

                            val content = TdApi.InputMessageText(
                                TdApi.FormattedText(commandText, emptyArray()), null, true
                            )
                            gateway.send(TdApi.SendMessage(chat.id, null, null, null, null, content)) { sendResult ->
                                val error = sendResult as? TdApi.Error
                                if (error == null) {
                                    if (isPrivate) sentUsers++ else sentGroups++
                                    retriedFloodWait = false
                                    next()
                                } else {
                                    val waitSeconds = Regex("(?i)(?:retry after|FLOOD_WAIT[_ ]?)(\\d+)")
                                        .find(error.message.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull()
                                    if (error.code == 429 && waitSeconds != null && !retriedFloodWait) {
                                        retriedFloodWait = true
                                        index--
                                        next((waitSeconds.coerceIn(1L, 60L) * 1000L) + 250L)
                                    } else {
                                        failed++
                                        retriedFloodWait = false
                                        next()
                                    }
                                }
                            }
                        }
                    }, delayMs.coerceAtLeast(0L), java.util.concurrent.TimeUnit.MILLISECONDS)
                }

                context.reply("Broadcast started for up to ${targets.size} main-dialog chats. Sending sequentially to respect Telegram limits.")
                next(0L)
            }
        }
    }
}
