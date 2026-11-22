package com.vikram.trondroid.plugins

import android.content.Context

data class PluginCommand(val name: String, val description: String, val usage: String, val example: String)
enum class PluginOwner(val label: String) { USERBOT("Userbot"), BOT("Bot") }

data class BotPlugin(
    val id: String,
    val title: String,
    val summary: String,
    val category: String,
    val owner: PluginOwner,
    val sourcePath: String,
    val commands: List<PluginCommand>,
    val implemented: Boolean = false
)

object PluginCatalog {
    private fun plugin(
        id: String, title: String, summary: String, category: String, owner: PluginOwner,
        source: String, ready: Boolean, vararg commands: String
    ) = BotPlugin(
        id, title, summary, category, owner, source,
        commands.map { name ->
            val usage = when (name) {
                in setOf("wiki", "imdb", "reddit", "r", "translate", "tr", "tl", "upcase", "joke", "weather", "currency", "paste", "google", "download", "upload", "ytsearch", "song", "lyrics", "whois", "id", "qrcode", "tts", "ud", "short", "unshort", "telegraph", "search", "pfp", "deezer", "spotify", "webshot", "itos", "vsubclip", "wlink", "commonchat", "noshort", "wtr") -> "$name <query>"
                in setOf("ban", "unban", "kick", "mute", "unmute", "promote", "demote") -> "$name <reply or user> [duration]"
                in setOf("whois", "uinfo") -> "$name <user id> (or reply to a user)"
                in setOf("mention", "men") -> "$name <user id> <display text>"
                "set" -> "set <fname|lname|bio|uname> <value>"
                "rem" -> "rem <lname|bio|uname>"
                "sleep" -> "sleep <seconds>"
                in setOf("q", "quotly") -> "$name [black|white|blue|purple|green] (reply to text)"
                in setOf("broadcast", "bdt") -> "$name <message>"
                in setOf("kbd", "keyboard") -> "$name <button text> | <https URL> | <message>"
                "img" -> "img [count 1-3] <query>"
                "pic" -> "pic <query>"
                "sauce" -> "sauce (reply to photo)"
                "tgm" -> "Reply to text or supported media with .tgm"
                "noshort" -> "noshort <shortened URL>"
                "wtr" -> "wtr <city>"
                "wlink" -> "wlink <word or phrase>"
                "json" -> "json (reply optional)"
                "mlink" -> "Reply to a message with .mlink"
                "saved" -> "Reply to a message with .saved"
                "forward" -> "Reply to a message with .forward [destination chat ID]"
                "commonchat" -> "commonchat <user id> (or reply to a user)"
                "speedtest" -> "speedtest"
                "adminlist", "admins" -> name
                "members", "bots" -> "$name 50"
                "gtitle" -> "gtitle New group title"
                "invite" -> "Reply to a user or use invite 123456789"
                "join" -> "join @publicgroup or join https://t.me/+inviteHash"
                "kickme" -> "kickme"
                "ytsearch" -> "ytsearch Kotlin Android tutorial"
                "ytinfo" -> "ytinfo https://youtu.be/dQw4w9WgXcQ"
                "vcut" -> "Reply to a video with .vcut 00:00:30"
                "vmute" -> "Reply to a video with .vmute"
                "vsubclip" -> "Reply to a video with .vsubclip 00:00:05 00:00:20"
                "undlt" -> "undlt [1-20]"
                in setOf("carbon", "cb") -> "$name [colour] <code>"
                "pin" -> "pin (reply required) [loud]"
                "unpin" -> "unpin (reply required) | unpin all"
                "banall" -> "banall confirm"
                in setOf("afk", "setwelcome", "welcome", "save", "setdv", "filter", "schedule", "settitle", "colourtemplate", "tts") -> "$name <text>"
                in setOf("purge", "purgecount", "spam", "dspam") -> "$name <count or options>"
                in setOf("members", "bots") -> "$name [1-100]"
                in setOf("vcut", "vmute", "vsubclip") -> "$name <time options>"
                in setOf("ytsearch", "ytinfo") -> "$name <query or YouTube URL>"
                in setOf("invite", "join") -> "$name <user id or public username/invite link>"
                else -> name
            }
            val example = when (name) {
                "broadcast", "bdt" -> "$name Hello everyone! Important announcement."
                "carbon", "cb" -> "$name aqua fun main() { println(\"Hello\") }"
                "carblist", "devinfo", "meminfo", "storageinfo", "displayinfo" -> name
                "eval", "e", "term", "shell" -> "$name (disabled for device safety)"
                "kbd", "keyboard" -> "$name Open website | https://example.com | Visit our website"
                "img" -> "img 3 mountain landscape"
                "pic" -> "pic sunset beach"
                "sauce" -> "Reply to a photo with .sauce"
                "send" -> "send userbot:stickers"
                "install" -> "Reply to a .tdplugin file with .install"
                "uninstall" -> "uninstall userbot:stickers"
                "sharedplugins" -> "sharedplugins"
                "song", "music", "deezer", "dz" -> "$name Blinding Lights The Weeknd"
                "lyrics", "ly" -> "$name Blinding Lights The Weeknd"
                "spotify" -> "spotify Daft Punk Get Lucky"
                "now" -> "now"
                "kang", "unkang", "stinfo", "stickerinfo" -> "Reply to a sticker with .$name"
                "reboot" -> "reboot"
                "sleep" -> "sleep 300"
                "q", "quotly" -> "Reply to a text message with .q blue"
                "whois", "uinfo" -> "$name 123456789"
                "mention", "men" -> "$name 123456789 Vikram"
                "set" -> "set bio Building with Kotlin"
                "rem" -> "rem bio"
                "wiki" -> "wiki quantum mechanics"
                "imdb" -> "imdb Interstellar"
                "translate", "tr", "tl" -> "$name en Hello, how are you?"
                "reddit", "r" -> "$name android"
                "weather" -> "weather Pune"
                "currency", "cur" -> "$name 100 USD INR"
                "qrcode" -> "qrcode https://example.com"
                "afk" -> "afk Having lunch"
                "ping","pong", "alive", "ialive", "help", "stats", "today", "time", "date" -> name
                else -> usage
            }
            PluginCommand(name, "$title command. $summary", usage, example)
        }, ready
    )

    val userbot = listOf(
        plugin("userbot:admin","Admin","Native TDLib moderation: ban/unban, timed mute, kick, promotion, demotion, pin management and confirmed supergroup bulk ban.","Moderation",PluginOwner.USERBOT,"main/userbot/modules/plugins/admin.py",true,"ban","dban","banall","unban","mute","dmute","unmute","kick","pin","unpin","promote","demote"),
        plugin("userbot:afk","AFK","Automatically manage away status and reply to mentions.","Automation",PluginOwner.USERBOT,"main/userbot/modules/plugins/afk.py",true,"afk","unafk"),
        plugin("userbot:alive","Alive","Show connected account and app runtime status.","Status",PluginOwner.USERBOT,"main/userbot/modules/plugins/alive.py",true,"alive"),
        plugin("userbot:anime","Anime","Fetch anime images and GIFs by action.","Entertainment",PluginOwner.USERBOT,"main/userbot/modules/plugins/anime.py",true,"animelist","nekopic","npic","animegif"),
        plugin("userbot:broadcast","Broadcast","Send announcements sequentially to private chats and groups in the main dialog list, with flood-wait handling and delivery totals.","Messaging",PluginOwner.USERBOT,"main/userbot/modules/plugins/broadcast.py",true,"broadcast","bdt"),
        plugin("userbot:carbon","Carbon","Render syntax-styled code images locally on-device with selectable accent colours.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/carbon.py",true,"carbon","cb","carblist"),
        plugin("userbot:clone","Clone","Copy and restore Telegram profile details.","Profile",PluginOwner.USERBOT,"main/userbot/modules/plugins/clone.py",true,"clone","revert"),
        plugin("userbot:dev","Developer tools","Read-only Android, app, memory, storage and display diagnostics. Arbitrary code evaluation and shell access are disabled for device safety.","Developer",PluginOwner.USERBOT,"main/userbot/modules/plugins/dev.py",true,"devinfo","meminfo","storageinfo","displayinfo","eval","e","term","shell"),
        plugin("userbot:download","Downloads","Download and upload supported Telegram files.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/download.py",true,"ls","download","upload","batchupload"),
        plugin("userbot:dv","Digital vault","Save and retrieve private snippets and vault entries locally on this device.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/dv.py",true,"setdv","deldv","getdv","pm","listdv","alldv"),
        plugin("userbot:filetools","File tools","Create, zip and extract files in the app local plugin storage.","Files",PluginOwner.USERBOT,"main/userbot/modules/plugins/filetools.py",true,"zip","unzip","new"),
        plugin("userbot:fun","Fun","Jokes, advice, text effects and lightweight entertainment.","Entertainment",PluginOwner.USERBOT,"main/userbot/modules/plugins/fun.py",true,"slap","upcase","type","insult","advice","uadvice","qs","wtd","mqt","joke"),
        plugin("userbot:google","Google & images","Search Wikimedia Commons for images and open Google Lens for user-controlled reverse-image lookup.","Search",PluginOwner.USERBOT,"main/userbot/modules/plugins/google.py",true,"sauce","pic","img"),
        plugin("userbot:group","Group info","Inspect group, supergroup, channel and unread chat details.","Information",PluginOwner.USERBOT,"main/userbot/modules/plugins/group.py",true,"basicgroup","supergroup","unreadchat","channel"),
        plugin("userbot:help","Help","List commands currently implemented in TronDroid.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/help.py",true,"help"),
        plugin("userbot:image","Image tools","Generate QR codes, convert static images and stickers, and fetch image or profile-picture content.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/image.py",true,"stoi","itos","qrcode","colourtemplate","cat","waifu","pfp","dog"),
        plugin("userbot:imdb","IMDb","Find movies and series using IMDb title search.","Search",PluginOwner.USERBOT,"main/userbot/modules/plugins/imdb.py",true,"imdb"),
        plugin("userbot:info","Media & chat info","Inspect media metadata and chat information.","Information",PluginOwner.USERBOT,"main/userbot/modules/plugins/info.py",true,"minfo","chatinfo"),
        plugin("userbot:keyboard","Keyboard","Create a one-button HTTPS inline keyboard in group chats, subject to Telegram account restrictions.","Messaging",PluginOwner.USERBOT,"main/userbot/modules/plugins/keyboard.py",true,"kbd","keyboard"),
        plugin("userbot:notes","Notes","Save, retrieve, list and clear personal notes.","Productivity",PluginOwner.USERBOT,"main/userbot/modules/plugins/notes.py",true,"save","notes","getnote","notelist","clear"),
        plugin("userbot:pastebin","Pastebin","Publish text snippets and return shareable links.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/pastebin.py",true,"paste","bin"),
        plugin("userbot:ping","Ping","Check the userbot command response.","Diagnostics",PluginOwner.USERBOT,"main/userbot/modules/plugins/ping.py",true,"ping","pong"),
        plugin("userbot:pmpermit","PM permit","Warn unknown private senders and block after a configurable message limit.","Privacy",PluginOwner.USERBOT,"main/userbot/modules/plugins/pmpermit.py",true,"pmpermit","approve","disapprove"),
        plugin("userbot:power","Power","Restart the active Telegram userbot session or temporarily pause its command and update processing. These actions do not reboot or power off Android.","System",PluginOwner.USERBOT,"main/userbot/modules/plugins/power.py",true,"reboot","sleep"),
        plugin("userbot:profile","Profile","Look up Telegram users and IDs, create mentions, block or unblock users, update your profile and send profile details to Saved Messages.","Profile",PluginOwner.USERBOT,"main/userbot/modules/plugins/profile.py",true,"whois","id","mention","men","uinfo","block","unblock","sg","set","rem","repo"),
        plugin("userbot:purge","Purge","Delete selected messages and count matching messages.","Moderation",PluginOwner.USERBOT,"main/userbot/modules/plugins/purge.py",true,"purge","purgecount","del"),
        plugin("userbot:quotly","Quotly","Render replied Telegram text locally into a shareable quote image with selectable colour themes; no third-party bot is required.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/quotly.py",true,"q","quotly"),
        plugin("userbot:reddit","Reddit","Search public Reddit posts and show relevant results.","Search",PluginOwner.USERBOT,"main/userbot/modules/plugins/reddit.py",true,"reddit","r"),
        plugin("userbot:share","Plugin sharing","Export, share, import and remove validated .tdplugin manifests. Imports are declarative metadata only; untrusted executable code is never loaded.","Plugins",PluginOwner.USERBOT,"main/userbot/modules/plugins/share.py",true,"send","install","uninstall","sharedplugins"),
        plugin("userbot:song","Music search","Search Deezer tracks, open official track pages, get 30-second previews and retrieve lyrics by matched artist/title.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/song.py",true,"song","music","deezer","dz","lyrics","ly"),
        plugin("userbot:spam","Spam tools","Repeat a message or text with bounded count and optional delay.","Messaging",PluginOwner.USERBOT,"main/userbot/modules/plugins/spam.py",true,"spam","dspam"),
        plugin("userbot:spotify","Spotify","Open Spotify search results. Current playback lookup is documented but requires a registered Spotify OAuth client and user-read-currently-playing permission.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/spotify.py",true,"spotify","now"),
        plugin("userbot:stats","Stats","Show Telegram dialog and chat statistics from loaded dialogs.","Information",PluginOwner.USERBOT,"main/userbot/modules/plugins/stats.py",true,"stats"),
        plugin("userbot:stickers","Stickers","Add or remove replied stickers from Telegram favorites and inspect sticker metadata. Sticker-pack creation from arbitrary images requires the official @Stickers bot.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/stickers.py",true,"kang","unkang","stinfo","stickerinfo"),
        plugin("userbot:sudo","Sudo users","Manage trusted user IDs allowed to run privileged commands.","Security",PluginOwner.USERBOT,"main/userbot/modules/plugins/sudo.py",true,"addsudo","listsudo","delsudo"),
        plugin("userbot:supertools","Super tools","Dictionary lookup, safe URL shortening and expansion, and webpage screenshot utilities. Text-to-speech and weather are available as separate native plugins.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/supertools.py",true,"ud","short","unshort","noshort","webshot"),
        plugin("userbot:telegraph","Telegraph","Publish replied text, photos, videos and supported media to Telegraph pages.","Publishing",PluginOwner.USERBOT,"main/userbot/modules/plugins/telegraph.py",true,"telegraph","tgm"),
        plugin("userbot:tts","Text to speech","Convert short text or a replied text message to an audio file.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/supertools.py",true,"tts"),
        plugin("userbot:time","Time & date","Show local time, date and weekday.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/time.py",true,"today","time","date"),
        plugin("userbot:tools","Tools","Search recent chat history, inspect message JSON, create message links, save or forward replies, find common groups, run a download speed test and view locally cached deleted messages.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/tools.py",true,"wlink","json","mlink","saved","forward","speedtest","commonchat","undlt"),
        plugin("userbot:translate","Translate","Translate text using the selected target language.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/translate.py",true,"translate","tr","tl","trlist","tllist","translatelist","translatelang"),
        plugin("userbot:update","Update","Check the latest published TronDroid release and open its release notes.","System",PluginOwner.USERBOT,"main/userbot/modules/plugins/update.py",true,"update"),
        plugin("userbot:utils","Group utilities","List administrators, recent members and bots; change the group title; invite one user; join a public group or invite link; or leave the current group. Custom administrator titles are not exposed by TDLib in this user session. Telegram permissions are enforced by Telegram.","Groups",PluginOwner.USERBOT,"main/userbot/modules/plugins/utils.py",true,"admins","adminlist","members","bots","gtitle","invite","join","kickme"),
        plugin("userbot:utube","YouTube","Search YouTube and inspect video metadata with official links. Stream extraction and downloading are intentionally not included.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/utube.py",true,"ytsearch","ytinfo"),
        plugin("userbot:videotools","Video tools","Native Android media remuxing for mute, trim and subclip operations. Inputs and outputs are limited to 100 MB and 10 minutes; trims are keyframe-aligned.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/videotools.py",true,"vcut","vmute","vsubclip"),
        plugin("userbot:welcome","Welcome","Configure text welcome messages for new group members.","Groups",PluginOwner.USERBOT,"main/userbot/modules/plugins/welcome.py",true,"welcome","setwelcome","delwelcome","getwelcome"),
        plugin("userbot:weather","Weather","Show current weather for a city.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/supertools.py",true,"weather","wtr"),
        plugin("userbot:currency","Currency","Convert between supported currencies.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/tools.py",true,"currency","cur"),
        plugin("userbot:temperature","Temperature","Convert Celsius and Fahrenheit.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/tools.py",true,"temp"),
        plugin("userbot:qrcode","QR code","Create a QR code for text or a URL.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/image.py",true,"qrcode"),
        plugin("userbot:wikipedia","Wikipedia","Search Wikipedia articles and read concise summaries.","Search",PluginOwner.USERBOT,"main/userbot/modules/plugins/wikipedia.py",true,"wiki"),
        plugin("userbot:zombies","Zombie cleanup","Find and remove deleted or inactive accounts from groups.","Moderation",PluginOwner.USERBOT,"main/userbot/modules/plugins/zombies.py",true,"zombies")
    )

    val bot = listOf(
        plugin("bot:botfun","Bot fun","Small group interaction and number game commands.","Entertainment",PluginOwner.BOT,"main/assistant/modules/plugins/botfun.py",true,"numbers"),
        plugin("bot:broadcast","Bot broadcast","Track users who start the bot and broadcast owner announcements to those users.","Messaging",PluginOwner.BOT,"main/assistant/modules/plugins/broadcast.py",true,"broadcast"),
        plugin("bot:eval","Bot evaluation","Owner-only code evaluation from the original server bot; not enabled in Android.","Developer",PluginOwner.BOT,"main/assistant/modules/plugins/eval.py",false,"eval"),
        plugin("bot:help","Help","Show the bot command list.","Utilities",PluginOwner.BOT,"main/assistant/modules/plugins/help.py",true,"help"),
        plugin("bot:ping","Ping","Check whether the Telegram bot is responding.","Diagnostics",PluginOwner.BOT,"main/assistant/modules/plugins/ping.py",true,"ping"),
        plugin("bot:start","Start","Show the bot welcome message and group-added notice.","Onboarding",PluginOwner.BOT,"main/assistant/modules/plugins/start.py",true,"start"),
        plugin("bot:utils","Bot utilities","Return chat and user IDs or an anime quote.","Utilities",PluginOwner.BOT,"main/assistant/modules/plugins/utils.py",true,"id","quote")
    )

    val all: List<BotPlugin> = userbot + bot
    fun find(id: String): BotPlugin? = all.firstOrNull { it.id == id }
}

class PluginSettings(context: Context) {
    private val prefs = context.getSharedPreferences("trondroid.plugins", Context.MODE_PRIVATE)
    fun enabled(id: String) = prefs.getBoolean(id, PluginCatalog.find(id)?.implemented ?: true)
    fun setEnabled(id: String, value: Boolean) { prefs.edit().putBoolean(id, value).apply() }

    private fun commandKey(pluginId: String, command: String) =
        "command:${pluginId.trim()}:${command.trim().lowercase()}"

    /** Commands are enabled by default; per-command state is independent of the parent plugin. */
    fun commandEnabled(pluginId: String, command: String): Boolean =
        prefs.getBoolean(commandKey(pluginId, command), true)

    fun setCommandEnabled(pluginId: String, command: String, value: Boolean) {
        prefs.edit().putBoolean(commandKey(pluginId, command), value).apply()
    }

    fun handler() = prefs.getString("command_handler", ".") ?: "."
    fun setHandler(value: String) { prefs.edit().putString("command_handler", value).apply() }
}
