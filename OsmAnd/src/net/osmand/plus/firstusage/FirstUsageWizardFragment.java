package net.osmand.plus.firstusage;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import net.osmand.plus.R;
import net.osmand.plus.base.BaseFullScreenFragment;
import net.osmand.plus.plugins.aprs.AprsAppDefaults;
import net.osmand.plus.plugins.aprs.AprsLocalServices;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;

/** PC-hosted setup replaces upstream region discovery and vector-map download offers. */
public class FirstUsageWizardFragment extends BaseFullScreenFragment implements FirstUsageActionsListener {
    public static final String TAG = "FirstUsageWizardFragment";
    public static final String FIRST_USAGE = "first_usage";
    public static final String SHOW_OSMAND_WELCOME_SCREEN = "show_osmand_welcome_screen";
    public static final int FIRST_USAGE_LOCATION_PERMISSION = 300;

    // Retained for compatibility with existing first-usage bottom sheets.
    public WizardType wizardType = WizardType.MAP_FOUND;
    public boolean deviceNightMode;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        updateNightMode();
        deviceNightMode = nightMode;
        LinearLayout content = new LinearLayout(getThemedInflater().getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = getResources().getDimensionPixelSize(R.dimen.content_padding);
        content.setPadding(padding, padding * 3, padding, padding * 3);
        content.setBackgroundColor(ColorUtilities.getColor(app, ColorUtilities.getActivityBgColorId(nightMode)));
        TextView title = new TextView(content.getContext());
        title.setText(R.string.aprs_welcome);
        title.setTextSize(26);
        title.setTextColor(ColorUtilities.getPrimaryTextColor(app, nightMode));
        content.addView(title);
        TextView description = new TextView(content.getContext());
        description.setText(R.string.aprs_pc_setup_description);
        description.setTextColor(ColorUtilities.getPrimaryTextColor(app, nightMode));
        description.setPadding(0, padding, 0, padding);
        content.addView(description);
        TextView server = new TextView(content.getContext());
        server.setText(AprsLocalServices.BASE_URL);
        server.setTextIsSelectable(true);
        server.setTextColor(ColorUtilities.getPrimaryTextColor(app, nightMode));
        content.addView(server);
        Button services = new Button(content.getContext());
        services.setText(R.string.aprs_services_title);
        services.setOnClickListener(v -> AprsLocalServices.show(requireActivity()));
        content.addView(services);
        Button start = new Button(content.getContext());
        start.setText(R.string.aprs_pc_setup_continue);
        start.setOnClickListener(v -> {
            if (AprsAppDefaults.applyOnce(app)) {
                closeWizard();
            } else {
                Toast.makeText(requireContext(), R.string.aprs_pc_setup_error, Toast.LENGTH_LONG).show();
            }
        });
        content.addView(start);
        ScrollView view = new ScrollView(content.getContext());
        view.setBackgroundColor(ColorUtilities.getColor(app, ColorUtilities.getActivityBgColorId(nightMode)));
        view.addView(content);
        return view;
    }

    public void closeWizard() {
        settings.SHOW_OSMAND_WELCOME_SCREEN.set(false);
        if (isAdded()) {
            requireActivity().getSupportFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }

    public void processLocationPermission(boolean granted) {
        // Position permissions are requested by the map's normal GPS flow.
    }

    @Override
    public void processActionClick(@NonNull FirstUsageAction action) {
        AprsLocalServices.show(requireActivity());
    }

    public static boolean showInstance(@NonNull FragmentActivity activity) {
        FragmentManager manager = activity.getSupportFragmentManager();
        if (AndroidUtils.isFragmentCanBeAdded(manager, TAG, true)) {
            manager.beginTransaction().replace(R.id.fragmentContainer, new FirstUsageWizardFragment(), TAG)
                    .commitAllowingStateLoss();
            return true;
        }
        return false;
    }

    @Override
    public String getAnalyticsScreen() {
        return "aprsisosm_pc_setup";
    }
}

interface FirstUsageActionsListener {
    void processActionClick(@NonNull FirstUsageAction action);
}
