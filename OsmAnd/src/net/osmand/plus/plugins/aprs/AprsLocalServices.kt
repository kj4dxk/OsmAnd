package net.osmand.plus.plugins.aprs

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import net.osmand.plus.R
import net.osmand.plus.base.BaseOsmAndDialogFragment
import net.osmand.plus.utils.AndroidUtils

/** User-owned services verified against C:/Transfer/osm/nginx.conf and osmand/SETUP.txt. */
object AprsLocalServices {
    const val BASE_URL = "http://pdeffenb.homeip.net:82"
    const val TILE_URL = "$BASE_URL/raster/light/{z}/{x}/{y}.png"
    const val ROUTING_URL = "$BASE_URL/osmand/route/v1/"

    @JvmStatic fun show(activity: FragmentActivity) = show(activity.supportFragmentManager)

    @JvmStatic fun show(manager: FragmentManager) {
        if (!manager.isStateSaved && manager.findFragmentByTag(TAG) == null) {
            AprsLocalServicesDialog().show(manager, TAG)
        }
    }

    private const val TAG = "aprsisosm.local.services"
}

/** Replaces upstream sales and hosted-service enrollment screens. */
class AprsLocalServicesDialog : BaseOsmAndDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = themedInflater.context
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(context).apply { setText(R.string.aprs_services_description) })
        fun open(label: Int, path: String) {
            content.addView(Button(context).apply {
                setText(label)
                setOnClickListener {
                    AndroidUtils.startActivityIfSafe(requireContext(),
                        Intent(Intent.ACTION_VIEW, Uri.parse(AprsLocalServices.BASE_URL + path)))
                }
            })
        }
        fun copy(label: Int, address: String) {
            content.addView(TextView(context).apply { text = address; setTextIsSelectable(true) })
            content.addView(Button(context).apply {
                setText(label)
                setOnClickListener {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(label), address))
                    Toast.makeText(context, R.string.aprs_address_copied, Toast.LENGTH_SHORT).show()
                }
            })
        }
        open(R.string.aprs_services_map, "/raster.html")
        open(R.string.aprs_services_setup, "/osmand-setup.html")
        open(R.string.aprs_services_monitor, "/monitor.html")
        copy(R.string.aprs_services_copy_tiles, AprsLocalServices.TILE_URL)
        copy(R.string.aprs_services_copy_routing, AprsLocalServices.ROUTING_URL)
        content.addView(TextView(context).apply { setText(R.string.aprs_services_unavailable) })
        return AlertDialog.Builder(context)
            .setTitle(R.string.aprs_services_title)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }
}
