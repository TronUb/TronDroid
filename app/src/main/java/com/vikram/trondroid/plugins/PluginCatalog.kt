package com.vikram.trondroid.plugins

import android.content.Context

data class PluginCommand(val name: String, val description: String, val usage: String)
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
            val usage = when {
                name in setOf("wiki", "imdb", "reddit", "r", "translate", "tr", "tl", "upcase", "joke", "weather", "currency", "paste", "google", "img", "download", "upload", "ytsearch", "song", "lyrics", "whois", "id") -> "$name <query/text>"
                name in setOf("ban", "mute", "kick", "promote", "demote", "pin", "unpin") -> "$name <reply/user>"
                else -> name
            }
            PluginCommand(name, "$title: $name command.", usage)
        }, ready
    )

    val userbot = listOf(
        plugin("userbot:admin","Admin","Group moderation, member management and permission controls.","Moderation",PluginOwner.USERBOT,"main/userbot/modules/plugins/admin.py",false,"ban","banall","unban","mute","unmute","kick","pin","unpin","promote","demote"),
        plugin("userbot:afk","AFK","Automatically manage away status and reply to mentions.","Automation",PluginOwner.USERBOT,"main/userbot/modules/plugins/afk.py",true,"afk","offlinemention","unafk"),
        plugin("userbot:alive","Alive","Show connected account and app runtime status.","Status",PluginOwner.USERBOT,"main/userbot/modules/plugins/alive.py",true,"alive","ialive"),
        plugin("userbot:anime","Anime","Fetch anime images and GIFs by action.","Entertainment",PluginOwner.USERBOT,"main/userbot/modules/plugins/anime.py",true,"animelist","nekopic","npic","animegif"),
        plugin("userbot:broadcast","Broadcast","Send an announcement to selected chats.","Messaging",PluginOwner.USERBOT,"main/userbot/modules/plugins/broadcast.py",false,"broadcast"),
        plugin("userbot:carbon","Carbon","Create syntax-highlighted code images. The upstream implementation is broken and remains unavailable until it is replaced.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/carbon.py",false,"carbon","carblist"),
        plugin("userbot:clone","Clone","Copy and restore Telegram profile details.","Profile",PluginOwner.USERBOT,"main/userbot/modules/plugins/clone.py",false,"clone","revert"),
        plugin("userbot:dev","Developer tools","Developer utilities and code evaluation. Shell execution is intentionally not enabled.","Developer",PluginOwner.USERBOT,"main/userbot/modules/plugins/dev.py",false,"eval","terminal"),
        plugin("userbot:download","Downloads","Download and upload supported Telegram files.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/download.py",false,"ls","download","upload","batchupload"),
        plugin("userbot:dv","Digital vault","Save and retrieve private snippets and vault entries.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/dv.py",false,"setdv","deldv","getdv","pm","listdv","alldv"),
        plugin("userbot:filetools","File tools","Create, zip and extract local files.","Files",PluginOwner.USERBOT,"main/userbot/modules/plugins/filetools.py",false,"zip","unzip","new"),
        plugin("userbot:fun","Fun","Jokes, advice, text effects and lightweight entertainment.","Entertainment",PluginOwner.USERBOT,"main/userbot/modules/plugins/fun.py",true,"slap","upcase","type","insult","advice","uadvice","qs","wtd","mqt","joke"),
        plugin("userbot:google","Google & images","Search the web and find image sources.","Search",PluginOwner.USERBOT,"main/userbot/modules/plugins/google.py",false,"sauce","pic","img"),
        plugin("userbot:group","Group info","Inspect group, supergroup, channel and unread chat details.","Information",PluginOwner.USERBOT,"main/userbot/modules/plugins/group.py",false,"basicgroup","supergroup","unreadchat","channel"),
        plugin("userbot:help","Help","List commands currently implemented in TronDroid.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/help.py",true,"help","uplugs","bplugs","inline"),
        plugin("userbot:image","Image tools","Generate QR codes and fetch image or profile-picture content.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/image.py",false,"stoi","itos","qrcode","colourtemplate","cat","waifu","pfp","dog"),
        plugin("userbot:imdb","IMDb","Find movies and series using IMDb title search.","Search",PluginOwner.USERBOT,"main/userbot/modules/plugins/imdb.py",true,"imdb"),
        plugin("userbot:info","Media & chat info","Inspect media metadata and chat information.","Information",PluginOwner.USERBOT,"main/userbot/modules/plugins/info.py",false,"minfo","chatinfo"),
        plugin("userbot:keyboard","Keyboard","Create custom reply keyboards.","Messaging",PluginOwner.USERBOT,"main/userbot/modules/plugins/keyboard.py",false,"keyboard"),
        plugin("userbot:notes","Notes","Save, retrieve, list and clear personal notes.","Productivity",PluginOwner.USERBOT,"main/userbot/modules/plugins/notes.py",true,"save","notes","getnote","notelist","clear"),
        plugin("userbot:pastebin","Pastebin","Publish text snippets and return shareable links.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/pastebin.py",true,"paste","bin"),
        plugin("userbot:ping","Ping","Check the userbot command response.","Diagnostics",PluginOwner.USERBOT,"main/userbot/modules/plugins/ping.py",true,"ping"),
        plugin("userbot:pmpermit","PM permit","Control private-message approval and allow-list behaviour.","Privacy",PluginOwner.USERBOT,"main/userbot/modules/plugins/pmpermit.py",false,"pmpermit","approve","disapprove"),
        plugin("userbot:power","Power","Device power actions from the original host environment are not available on Android.","System",PluginOwner.USERBOT,"main/userbot/modules/plugins/power.py",false,"reboot","sleep"),
        plugin("userbot:profile","Profile","Look up Telegram users, IDs and profile-related information.","Profile",PluginOwner.USERBOT,"main/userbot/modules/plugins/profile.py",false,"whois","id","mention","uinfo","block","unblock","sg","set","rem","repo"),
        plugin("userbot:purge","Purge","Delete selected messages and count matching messages.","Moderation",PluginOwner.USERBOT,"main/userbot/modules/plugins/purge.py",false,"purge","purgecount","del"),
        plugin("userbot:quotly","Quotly","Turn a replied message into a quote image.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/quotly.py",false,"quotly"),
        plugin("userbot:reddit","Reddit","Search public Reddit posts and show relevant results.","Search",PluginOwner.USERBOT,"main/userbot/modules/plugins/reddit.py",true,"reddit","r"),
        plugin("userbot:share","Plugin sharing","Share, install and uninstall compatible plugin packages.","Plugins",PluginOwner.USERBOT,"main/userbot/modules/plugins/share.py",false,"send","install","uninstall"),
        plugin("userbot:song","Music search","Search songs, Deezer tracks and lyrics.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/song.py",false,"song","deezer","lyrics"),
        plugin("userbot:spam","Spam tools","Repeated-message utilities from the source project; disabled by default.","Messaging",PluginOwner.USERBOT,"main/userbot/modules/plugins/spam.py",false,"spam","dspam"),
        plugin("userbot:spotify","Spotify","Search Spotify and inspect the currently playing track.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/spotify.py",false,"spotify","now"),
        plugin("userbot:stats","Stats","Show Telegram dialog and chat statistics.","Information",PluginOwner.USERBOT,"main/userbot/modules/plugins/stats.py",false,"stats"),
        plugin("userbot:stickers","Stickers","Create stickers from media and inspect sticker metadata.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/stickers.py",false,"kang","stinfo"),
        plugin("userbot:sudo","Sudo users","Manage trusted user IDs allowed to run privileged commands.","Security",PluginOwner.USERBOT,"main/userbot/modules/plugins/sudo.py",false,"addsudo","listsudo","delsudo"),
        plugin("userbot:supertools","Super tools","Text-to-speech, dictionary, link, weather and web utilities.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/supertools.py",false,"tts","ud","short","unshort","weather","webshot","undlt"),
        plugin("userbot:telegraph","Telegraph","Publish text or media to Telegraph pages.","Publishing",PluginOwner.USERBOT,"main/userbot/modules/plugins/telegraph.py",false,"telegraph"),
        plugin("userbot:time","Time & date","Show local time, date and weekday.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/time.py",true,"today","time","date"),
        plugin("userbot:tools","Tools","Currency, temperature, saved messages and message utilities.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/tools.py",false,"wlink","currency","temperature","json","mlink","saved","forward","speedtest","commonchat"),
        plugin("userbot:translate","Translate","Translate text using the selected target language.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/translate.py",true,"translate","translatelang"),
        plugin("userbot:update","Update","The original update workflow targets its server deployment; Android app updates are handled separately.","System",PluginOwner.USERBOT,"main/userbot/modules/plugins/update.py",false,"update"),
        plugin("userbot:utube","YouTube","Search videos, inspect video information and download media.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/utube.py",false,"ytsearch","ytinfo","ytmdl"),
        plugin("userbot:videotools","Video tools","Trim, mute, invert, speed and transform video or audio.","Media",PluginOwner.USERBOT,"main/userbot/modules/plugins/videotools.py",false,"vcut","vinvert","vmute","vsubclip","vafadein","vafadeout","vsetaudio","vspeed","vfadeout","vfadein"),
        plugin("userbot:welcome","Welcome","Configure and send group welcome messages.","Groups",PluginOwner.USERBOT,"main/userbot/modules/plugins/welcome.py",false,"welcome","setwelcome","delwelcome","getwelcome"),
        plugin("userbot:weather","Weather","Show current weather for a city.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/supertools.py",true,"weather"),
        plugin("userbot:currency","Currency","Convert between supported currencies.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/tools.py",true,"currency","cur"),
        plugin("userbot:temperature","Temperature","Convert Celsius and Fahrenheit.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/tools.py",true,"temp"),
        plugin("userbot:qrcode","QR code","Create a QR code for text or a URL.","Utilities",PluginOwner.USERBOT,"main/userbot/modules/plugins/image.py",true,"qrcode"),
        plugin("userbot:wikipedia","Wikipedia","Search Wikipedia articles and read concise summaries.","Search",PluginOwner.USERBOT,"main/userbot/modules/plugins/wikipedia.py",true,"wiki"),
        plugin("userbot:zombies","Zombie cleanup","Find and remove deleted or inactive accounts from groups.","Moderation",PluginOwner.USERBOT,"main/userbot/modules/plugins/zombies.py",false,"zombies")
    )

    val bot = listOf(
        plugin("bot:botfun","Bot fun","Small group interaction and number game commands.","Entertainment",PluginOwner.BOT,"main/assistant/modules/plugins/botfun.py",true,"numbers"),
        plugin("bot:broadcast","Bot broadcast","Broadcast updates and handle bot start tracking.","Messaging",PluginOwner.BOT,"main/assistant/modules/plugins/broadcast.py",false,"broadcast"),
        plugin("bot:eval","Bot evaluation","Owner-only code evaluation from the original server bot; not enabled in Android.","Developer",PluginOwner.BOT,"main/assistant/modules/plugins/eval.py",false,"eval"),
        plugin("bot:help","Help","Show the bot command list.","Utilities",PluginOwner.BOT,"main/assistant/modules/plugins/help.py",true,"help"),
        plugin("bot:ping","Ping","Check whether the Telegram bot is responding.","Diagnostics",PluginOwner.BOT,"main/assistant/modules/plugins/ping.py",true,"ping"),
        plugin("bot:start","Start","Show the bot welcome message and group-added notice.","Onboarding",PluginOwner.BOT,"main/assistant/modules/plugins/start.py",true,"start"),
        plugin("bot:utils","Bot utilities","Return chat and user IDs or an anime quote.","Utilities",PluginOwner.BOT,"main/assistant/modules/plugins/utils.py",false,"id","quote")
    )

    val all: List<BotPlugin> = userbot + bot
    fun find(id: String): BotPlugin? = all.firstOrNull { it.id == id }
}

class PluginSettings(context: Context) {
    private val prefs = context.getSharedPreferences("trondroid.plugins", Context.MODE_PRIVATE)
    fun enabled(id: String) = prefs.getBoolean(id, PluginCatalog.find(id)?.implemented ?: true)
    fun setEnabled(id: String, value: Boolean) { prefs.edit().putBoolean(id, value).apply() }
    fun handler() = prefs.getString("command_handler", ".") ?: "."
    fun setHandler(value: String) { prefs.edit().putString("command_handler", value).apply() }
}
