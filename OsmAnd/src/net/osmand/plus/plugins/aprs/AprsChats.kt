package net.osmand.plus.plugins.aprs

import android.app.Dialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import net.osmand.plus.R
import net.osmand.plus.base.BaseOsmAndDialogFragment
import net.osmand.shared.aprs.AprsMessages
import net.osmand.shared.aprs.AprsProtocol
import java.text.DateFormat
import java.util.Date

object AprsChats {
    fun account(activity: FragmentActivity) = activity.getSharedPreferences("aprs", 0).getString("callsign", "")!!.uppercase()
    fun inbox(activity: FragmentActivity) {
        val manager = activity.supportFragmentManager
        if (!manager.isStateSaved && manager.findFragmentByTag("aprs.inbox") == null) AprsInboxDialog().show(manager, "aprs.inbox")
    }
    fun conversation(activity: FragmentActivity, peer: String) {
        val account = account(activity)
        if (!AprsProtocol.validCallsign(account)) {
            Toast.makeText(activity, R.string.aprs_chat_setup, Toast.LENGTH_LONG).show(); return
        }
        if (!AprsProtocol.validCallsign(peer) || peer == account) return
        val manager = activity.supportFragmentManager
        if (!manager.isStateSaved && manager.findFragmentByTag("aprs.conversation") == null) {
            AprsConversationDialog().apply { arguments = Bundle().apply { putString("account", account); putString("peer", peer) } }
                .show(manager, "aprs.conversation")
        }
    }
}

class AprsInboxDialog : BaseOsmAndDialogFragment() {
    private lateinit var list: LinearLayout
    private var collector: Job? = null
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = themedInflater.context
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        return AlertDialog.Builder(context).setTitle(R.string.aprs_messages)
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton(R.string.aprs_new_chat) { _, _ ->
                val activity = requireActivity()
                val input = EditText(context).apply {
                    hint = getString(R.string.aprs_chat_call_hint)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    filters = arrayOf(InputFilter.LengthFilter(9)); setSingleLine()
                }
                val picker = AlertDialog.Builder(context).setTitle(R.string.aprs_new_chat).setView(input)
                    .setPositiveButton(R.string.aprs_message_station, null).setNegativeButton(android.R.string.cancel, null).create()
                picker.setOnShowListener {
                    picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val call = input.text.toString().trim().uppercase()
                        if (!AprsProtocol.validCallsign(call) || call == AprsChats.account(activity)) input.error = context.getString(R.string.aprs_invalid_settings)
                        else { picker.dismiss(); AprsChats.conversation(activity, call) }
                    }
                }
                picker.show()
            }.setNegativeButton(R.string.shared_string_close, null).create()
    }
    override fun onStart() {
        super.onStart()
        collector?.cancel()
        collector = lifecycleScope.launch {
            val store = AprsChatStore.get(requireContext())
            store.revision.collect {
                val chats = store.chats(AprsChats.account(requireActivity()))
                list.removeAllViews()
                if (chats.isEmpty()) list.addView(TextView(list.context).apply { setText(R.string.aprs_no_chats) })
                chats.forEach { chat ->
                    list.addView(Button(list.context).apply {
                        isAllCaps = false
                        text = getString(R.string.aprs_chat_summary, chat.peer, chat.unread,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(chat.time)), chat.preview)
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setOnClickListener { AprsChats.conversation(requireActivity(), chat.peer) }
                    })
                }
            }
        }
    }
    override fun onStop() { collector?.cancel(); super.onStop() }
}

class AprsConversationDialog : BaseOsmAndDialogFragment() {
    private lateinit var history: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var counter: TextView
    private lateinit var connection: TextView
    private var collector: Job? = null
    private val account get() = requireArguments().getString("account")!!
    private val peer get() = requireArguments().getString("peer")!!
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = themedInflater.context
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(context).apply { text = getString(R.string.aprs_chat_title, peer); textSize = 20f },
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(Button(context).apply { setText(R.string.shared_string_close); setOnClickListener { dismiss() } })
        content.addView(heading)
        val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        content.setBackgroundColor(colors.getColor(0, android.graphics.Color.WHITE)); colors.recycle()
        connection = TextView(context)
        content.addView(connection)
        history = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(context).apply { addView(history) }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(TextView(context).apply { setText(R.string.aprs_chat_protocol_notice) })
        input = EditText(context).apply {
            hint = getString(R.string.aprs_chat_compose)
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(savedInstanceState?.getString("draft", ""))
        }
        content.addView(input)
        counter = TextView(context)
        content.addView(counter)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                counter.text = getString(R.string.aprs_chat_counter, s?.length ?: 0)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        counter.text = getString(R.string.aprs_chat_counter, input.text.length)
        val send = Button(context).apply { setText(R.string.aprs_chat_send) }
        content.addView(send)
        send.setOnClickListener {
            val text = input.text.toString()
            if (!AprsMessages.validText(text)) { input.error = getString(R.string.aprs_chat_invalid); return@setOnClickListener }
            send.isEnabled = false
            lifecycleScope.launch {
                try {
                    AprsChatStore.get(requireContext()).enqueue(account, peer, text)
                    input.text.clear()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Toast.makeText(context, R.string.aprs_chat_storage_error, Toast.LENGTH_LONG).show()
                } finally { send.isEnabled = true }
            }
        }
        return Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(content, ViewGroup.LayoutParams(-1, -1))
        }
    }
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        collector?.cancel()
        collector = lifecycleScope.launch {
            val store = AprsChatStore.get(requireContext())
            launch {
                AprsService.client.state.collect { state ->
                    connection.text = getString(R.string.aprs_chat_connection, account, state.name)
                }
            }
            store.revision.collect {
                val messages = store.messages(account, peer)
                val atBottom = !scroll.canScrollVertically(1)
                history.removeAllViews()
                messages.forEach { message ->
                    history.addView(TextView(history.context).apply {
                        val who = if (message.outgoing) account else peer
                        val status = when(message.status) {
                            "QUEUED" -> R.string.aprs_chat_queued
                            "SENDING" -> R.string.aprs_chat_sending
                            "WAITING" -> R.string.aprs_chat_waiting
                            "ACKNOWLEDGED" -> R.string.aprs_chat_acknowledged
                            "REJECTED" -> R.string.aprs_chat_rejected
                            "NO_ACK" -> R.string.aprs_chat_no_ack
                            else -> R.string.aprs_chat_received
                        }
                        text = getString(R.string.aprs_chat_message, who,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.time)),
                            message.body, getString(status))
                        setPadding(12, 16, 12, 16); setTextIsSelectable(true)
                        gravity = if (message.outgoing) Gravity.END else Gravity.START
                    })
                }
                store.markRead(account, peer)
                if(atBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }
    override fun onStop() { collector?.cancel(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("draft", input.text.toString()); super.onSaveInstanceState(outState) }
}
