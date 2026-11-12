package com.whereq.dosbox;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The game launcher: a panel over the emulator that lists the installed
 * bundles and starts them.
 *
 * <p>It is drawn on top of the SDL surface, so it covers the emulator while
 * it is open and hands the screen back when it is closed.
 */
public class GameLauncherView extends FrameLayout {

    /** What the user asked the launcher to do. */
    public interface Listener {
        /** Open the system file picker for a zip archive. */
        void onInstallRequested();

        /** Import every archive of a folder on the phone. */
        void onBrowseRequested();

        /** Start the game. */
        void onPlayRequested(GameBundle bundle);

        /** Pick a different file to start. */
        void onEntryPointRequested(GameBundle bundle);

        /** A different file was picked as the entry point. */
        void onEntryPointChosen(GameBundle bundle, String relativePath);

        /** Remove the game. */
        void onDeleteRequested(GameBundle bundle);

        /** Close the panel. */
        void onCloseRequested();
    }

    private static final int COLOR_BACKGROUND = 0xF0111319;
    private static final int COLOR_SURFACE = 0xFF1B1F27;
    private static final int COLOR_SURFACE_ALT = 0xFF232833;
    private static final int COLOR_TEXT = 0xFFE8EAED;
    private static final int COLOR_DIM = 0xFF9AA0AA;
    private static final int COLOR_ACCENT = 0xFFF5A623;

    private final Listener listener;
    private final LinearLayout list;
    private ScrollView scroll;
    private final TextView status;
    private final TextView empty;

    public GameLauncherView(Context context, Listener listener) {
        super(context);
        this.listener = listener;

        setBackgroundColor(COLOR_BACKGROUND);
        setClickable(true);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LayoutParams columnParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        columnParams.gravity = Gravity.CENTER;
        addView(column, columnParams);

        // The panel itself is a card in the middle of the screen, so the
        // emulator stays visible around it.
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setBackground(cardBackground());
        LayoutParams panelParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER;
        panelParams.leftMargin = dp(12);
        panelParams.rightMargin = dp(12);
        column.addView(panel, panelParams);

        // ---------------------------------------------------------- header ---
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header);

        TextView title = new TextView(context);
        title.setText("Games");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button close = makeButton("Close");
        close.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                GameLauncherView.this.listener.onCloseRequested();
            }
        });
        header.addView(close);

        // --------------------------------------------------------- install ---
        Button install = makeButton("+  Install a game bundle (.zip)");
        install.setTextColor(0xFF10202E);
        install.setBackgroundColor(COLOR_ACCENT);
        LinearLayout.LayoutParams installParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        installParams.topMargin = dp(12);
        installParams.bottomMargin = dp(10);
        panel.addView(install, installParams);
        install.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                GameLauncherView.this.listener.onInstallRequested();
            }
        });

        // The shorter way in: every archive of a folder is installed at once.
        Button browse = makeButton("Install all bundles from a folder");
        browse.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                GameLauncherView.this.listener.onBrowseRequested();
            }
        });
        LinearLayout.LayoutParams browseParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        browseParams.bottomMargin = dp(10);
        browseParams.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(browse, browseParams);

        // ------------------------------------------------------- the games ---
        empty = new TextView(context);
        empty.setText("No games installed yet.\n\nInstall a .zip bundle from your phone and it is unpacked here, ready to run.");
        empty.setTextColor(COLOR_DIM);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(8), dp(24), dp(8), dp(24));
        panel.addView(empty);

        scroll = new ScrollView(context);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        // ---------------------------------------------------------- status ---
        status = new TextView(context);
        status.setTextColor(COLOR_DIM);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(0, dp(10), 0, 0);
        panel.addView(status);
    }

    /**
     * Rebuilds the list of games.
     */
    public void showGames(java.util.List<GameBundle> bundles) {
        list.removeAllViews();
        empty.setVisibility(bundles.isEmpty() ? VISIBLE : GONE);
        for (final GameBundle bundle : bundles) {
            list.addView(makeRow(bundle));
        }
        // The list is only as tall as it needs to be, so the card stays
        // small - but it is capped, so a long list cannot push the card off
        // the screen.
        scroll.post(new Runnable() {
            public void run() {
                int wanted = list.getMeasuredHeight();
                int capped = Math.min(wanted, dp(320));
                if (scroll.getLayoutParams().height != capped) {
                    scroll.getLayoutParams().height = capped;
                    scroll.requestLayout();
                }
            }
        });
    }

    /**
     * Shows a message below the list.
     */
    public void setStatus(String text) {
        status.setText(text == null ? "" : text);
    }

    private View makeRow(final GameBundle bundle) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(COLOR_SURFACE_ALT);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);

        TextView name = new TextView(getContext());
        name.setText(bundle.displayName);
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        row.addView(name);

        StringBuilder info = new StringBuilder();
        info.append(bundle.entry).append("   ·   ")
                .append(formatSize(bundle.sizeBytes));
        if (bundle.hasOwnDosboxConfig) {
            info.append("   ·   ships its own dosbox.conf");
        }
        TextView detail = new TextView(getContext());
        detail.setText(info.toString());
        detail.setTextColor(COLOR_DIM);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(detail);

        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(6);
        row.addView(actions, actionsParams);

        Button play = makeButton("Play");
        play.setTextColor(0xFF10202E);
        play.setBackgroundColor(COLOR_ACCENT);
        play.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                GameLauncherView.this.listener.onPlayRequested(bundle);
            }
        });
        actions.addView(play);

        Button files = makeButton("Start file…");
        files.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                GameLauncherView.this.listener.onEntryPointRequested(bundle);
            }
        });
        actions.addView(files);

        Button delete = makeButton("Remove");
        delete.setTextColor(COLOR_ACCENT);
        delete.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                GameLauncherView.this.listener.onDeleteRequested(bundle);
            }
        });
        actions.addView(delete);

        return row;
    }

    /**
     * Shows the list of files a bundle could be started from.
     */
    public void showEntryPointPicker(final GameBundle bundle,
                                     final java.util.List<String> candidates) {
        if (candidates.isEmpty()) {
            setStatus("This bundle has no .bat, .exe or .com file.");
            return;
        }
        final String[] items = candidates.toArray(new String[candidates.size()]);
        new AlertDialog.Builder(getContext())
                .setTitle("Start which file?")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        setStatus("Start file set to " + items[which]);
                        GameLauncherView.this.listener.onEntryPointChosen(bundle, items[which]);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The launcher panel, as a rounded card over the emulator. */
    private android.graphics.drawable.GradientDrawable cardBackground() {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setColor(COLOR_SURFACE);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), 0xFF2E3542);
        return drawable;
    }

    private Button makeButton(String label) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setAllCaps(false);
        button.setBackgroundColor(COLOR_SURFACE_ALT);
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    static String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        if (bytes >= 1024) {
            return (bytes / 1024) + " KB";
        }
        return bytes + " B";
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
