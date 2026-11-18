package net.osmand.plus.plugins.aprs

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.osmand.shared.aprs.AprsMessage
import net.osmand.shared.aprs.AprsMessages
import net.osmand.shared.aprs.AprsProtocol
import java.util.UUID

/** Private app storage; account and full peer SSID isolate separate conversations. */
class AprsChatStore private constructor(context: Context) : SQLiteOpenHelper(context, "aprs_chats.db", null, 1) {
    val revision = MutableStateFlow(0L)
    private val mutex = Mutex()
    data class Message(val row: Long, val account: String, val peer: String, val body: String,
        val outgoing: Boolean, val wireId: String?, val time: Long, val status: String,
        val attempts: Int, val lastSend: Long)
    data class Chat(val peer: String, val preview: String, val time: Long, val unread: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (_id INTEGER PRIMARY KEY AUTOINCREMENT,
            account TEXT NOT NULL, peer TEXT NOT NULL, body TEXT NOT NULL, outgoing INTEGER NOT NULL,
            wire_id TEXT, time INTEGER NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            last_send INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX conversation ON messages(account,peer,time,_id)")
        db.execSQL("CREATE INDEX outbox ON messages(account,outgoing,status)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    private fun changed() { revision.value += 1 }
    private fun rows(where: String, args: Array<String>): List<Message> =
        readableDatabase.query("messages", null, where, args, null, null, "time,_id").use { c ->
            buildList { while (c.moveToNext()) {
                fun str(n: String) = c.getString(c.getColumnIndexOrThrow(n))
                fun num(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
                add(Message(num("_id"), str("account"), str("peer"), str("body"), num("outgoing") == 1L,
                    if(c.isNull(c.getColumnIndexOrThrow("wire_id"))) null else str("wire_id"), num("time"), str("status"), num("attempts").toInt(), num("last_send")))
            } }
        }
    suspend fun messages(account: String, peer: String) = access { rows("account=? AND peer=?", arrayOf(account, peer)) }
    suspend fun chats(account: String): List<Chat> = access {
        readableDatabase.rawQuery("""SELECT m.peer,m.body,m.time,
            (SELECT COUNT(*) FROM messages u WHERE u.account=m.account AND u.peer=m.peer AND u.unread=1)
            FROM messages m WHERE m.account=? AND m._id=(SELECT n._id FROM messages n
            WHERE n.account=m.account AND n.peer=m.peer ORDER BY n.time DESC,n._id DESC LIMIT 1)
            ORDER BY m.time DESC,m._id DESC""", arrayOf(account)).use { c ->
            buildList { while(c.moveToNext()) add(Chat(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3))) }
        }
    }
    suspend fun enqueue(account: String, peer: String, text: String) = access {
        require(AprsProtocol.validCallsign(account) && AprsProtocol.validCallsign(peer) && peer != account)
        require(AprsMessages.validText(text))
        var id: String
        do { id = UUID.randomUUID().toString().replace("-", "").take(5).uppercase() }
        while (rows("account=? AND outgoing=1 AND wire_id=?", arrayOf(account, id)).isNotEmpty())
        writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("account", account); put("peer", peer); put("body", text); put("outgoing", 1)
            put("wire_id", id); put("time", System.currentTimeMillis()); put("status", "QUEUED")
        })
        changed()
    }
    suspend fun receive(account: String, message: AprsMessage): Boolean = access {
        if (message.recipient != account || message.sender == account) return@access false
        fun confirm(id: String, rejected: Boolean) {
            writableDatabase.update("messages", ContentValues().apply { put("status", if(rejected) "REJECTED" else "ACKNOWLEDGED") },
                "account=? AND peer=? AND outgoing=1 AND wire_id=? AND status!='REJECTED'",
                arrayOf(account, message.sender, id))
        }
        message.acknowledgement?.let { confirm(it, message.rejected) }
        message.replyAck?.let { confirm(it, false) }
        if (message.acknowledgement != null) { changed(); return@access false }
        val now = System.currentTimeMillis()
        if (message.id != null && rows("account=? AND peer=? AND outgoing=0 AND wire_id=? AND body=? AND time>=?",
            arrayOf(account, message.sender, message.id!!, message.text, (now - 86_400_000).toString())).isNotEmpty()) {
            changed(); return@access false
        }
        writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("account", account); put("peer", message.sender); put("body", message.text); put("outgoing", 0)
            put("wire_id", message.id); put("time", now); put("status", "RECEIVED"); put("unread", 1)
        })
        changed(); true
    }
    suspend fun markRead(account: String, peer: String) = access {
        if(writableDatabase.update("messages", ContentValues().apply { put("unread", 0) },
            "account=? AND peer=? AND unread=1", arrayOf(account, peer)) > 0) changed()
    }
    suspend fun pending(account: String): List<Message> = access {
        val now = System.currentTimeMillis()
        writableDatabase.update("messages", ContentValues().apply { put("status", "NO_ACK") },
            "account=? AND outgoing=1 AND status IN ('QUEUED','WAITING','SENDING') AND (time<? OR (attempts>=5 AND last_send<?))",
            arrayOf(account, (now - 900_000).toString(), (now - 30_000).toString())).also { if(it > 0) changed() }
        rows("account=? AND outgoing=1 AND status IN ('QUEUED','WAITING','SENDING') AND attempts<5 AND last_send<?",
            arrayOf(account, (now - 30_000).toString())).take(1)
    }
    suspend fun attempted(row: Long) = access {
        writableDatabase.execSQL("UPDATE messages SET status='SENDING',attempts=attempts+1,last_send=? WHERE _id=? AND status IN ('QUEUED','WAITING','SENDING')",
            arrayOf(System.currentTimeMillis(), row)); changed()
    }
    suspend fun transmitted(row: Long, sent: Boolean) = access {
        writableDatabase.update("messages", ContentValues().apply { put("status", if(sent) "WAITING" else "QUEUED") },
            "_id=? AND status='SENDING'", arrayOf(row.toString())); changed()
    }
    companion object {
        @Volatile private var instance: AprsChatStore? = null
        fun get(context: Context): AprsChatStore = instance ?: synchronized(this) {
            instance ?: AprsChatStore(context.applicationContext).also { instance = it }
        }
    }
}
