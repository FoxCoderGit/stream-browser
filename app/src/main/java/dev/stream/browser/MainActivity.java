package dev.stream.browser;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import java.io.File;
import java.util.*;
import org.json.JSONObject;
import org.mozilla.geckoview.*;

public final class MainActivity extends Activity {
    private BrowserApp app;
    private BrowserLibrary library;
    private GeckoView browser;
    private View home;
    private FrameLayout browserFrame;
    private LinearLayout toolbar;
    private EditText address;
    private Button media;
    private TextView status;
    private View rootView;
    private LinearLayout mediaList;
    private BottomSheetDialog mediaSheet;
    private boolean canGoBack;
    private View backButton;
    private boolean atHome;
    private String pageTitle = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }
    private int tone(int attribute) { return MaterialColors.getColor(rootView, attribute); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        DynamicColors.applyToActivityIfAvailable(this);
        app = (BrowserApp)getApplication(); app.initBrowser();
        library = new BrowserLibrary(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        rootView = root;
        root.setBackgroundColor(tone(com.google.android.material.R.attr.colorSurface));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets systemBars = Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(WindowInsets.Type.systemBars()) : insets.getSystemWindowInsets();
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom); return insets;
        });
        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(8), dp(8));
        address = new EditText(this); address.setSingleLine(); address.setTextSize(14);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setHint("Поиск или адрес");
        address.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_GO) return false;
            navigate(); return true;
        });
        MaterialCardView addressCard = new MaterialCardView(this);
        addressCard.setRadius(dp(28));
        addressCard.setCardElevation(0);
        addressCard.setStrokeWidth(0);
        addressCard.setCardBackgroundColor(tone(com.google.android.material.R.attr.colorSurfaceContainer));
        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(14), 0, dp(2), 0);
        addressRow.addView(icon(R.drawable.ms_language, com.google.android.material.R.attr.colorOnSurfaceVariant),
            new LinearLayout.LayoutParams(dp(22), dp(22)));
        address.setBackgroundColor(Color.TRANSPARENT);
        address.setPadding(dp(10), 0, 0, 0);
        addressRow.addView(address, new LinearLayout.LayoutParams(0, -1, 1));
        addressRow.addView(iconButton(R.drawable.ms_arrow_forward, "Открыть адрес", v -> navigate()),
            new LinearLayout.LayoutParams(dp(48), dp(48)));
        addressCard.addView(addressRow, new FrameLayout.LayoutParams(-1, -1));
        toolbar.addView(addressCard, new LinearLayout.LayoutParams(0, dp(52), 1));
        toolbar.addView(iconButton(R.drawable.ms_more_horiz, "Действия браузера", v -> menu()),
            new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);
        status = new TextView(this); status.setTextSize(12);
        status.setTextColor(tone(com.google.android.material.R.attr.colorOnSurfaceVariant));
        status.setPadding(dp(20), dp(4), dp(16), dp(8));
        root.addView(status);
        FrameLayout frame = new FrameLayout(this);
        browserFrame = frame;
        browser = new GeckoView(this); browser.setSession(app.session);
        frame.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        ExtendedFloatingActionButton mediaButton = new ExtendedFloatingActionButton(this);
        mediaButton.setText("Медиа");
        mediaButton.setIconResource(R.drawable.ms_download);
        mediaButton.setIconTint(android.content.res.ColorStateList.valueOf(tone(com.google.android.material.R.attr.colorOnPrimaryContainer)));
        mediaButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tone(com.google.android.material.R.attr.colorPrimaryContainer)));
        mediaButton.setTextColor(tone(com.google.android.material.R.attr.colorOnPrimaryContainer));
        mediaButton.setOnClickListener(v -> showMedia());
        media = mediaButton;
        FrameLayout.LayoutParams floating = new FrameLayout.LayoutParams(-2, dp(56), Gravity.BOTTOM | Gravity.END);
        floating.setMargins(dp(16), dp(16), dp(20), dp(20)); frame.addView(media, floating);
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(this); bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(3), dp(8), dp(4));
        bottom.setBackgroundColor(tone(com.google.android.material.R.attr.colorSurfaceContainer));
        backButton = bottomAction(bottom, R.drawable.ms_arrow_back, "Назад", v -> back());
        bottomAction(bottom, R.drawable.ms_home, "Главная", v -> showHome());
        bottomAction(bottom, R.drawable.ms_download, "Загрузки", v -> showDownloads());
        bottomAction(bottom, R.drawable.ms_settings, "Настройки", v -> settings());
        root.addView(bottom); setContentView(root);
        boolean light = MaterialColors.isColorLight(tone(com.google.android.material.R.attr.colorSurface));
        androidx.core.view.WindowInsetsControllerCompat bars =
            androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(light);
        bars.setAppearanceLightNavigationBars(light);
        app.onMedia = this::updateMedia;
        app.session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession session, boolean value) {
                canGoBack = value;
                updateBack();
            }
            @Override public void onLocationChange(GeckoSession session, String url,
                List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                if (url != null && !url.equals(app.page)) {
                    app.page = url;
                    if (!atHome) address.setText(url);
                    app.clearMedia();
                }
            }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
                app.session.loadUri(uri); return GeckoResult.fromValue(null);
            }
        });
        app.session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession session, String url) {
                pageTitle = "";
                app.clearMedia();
            }
        });
        app.session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onTitleChange(GeckoSession session, String title) { pageTitle = title; }
        });
        updateMedia();
        if (saved == null && !app.session.isOpen()) app.session.open(app.runtime);
        if (saved == null || saved.getBoolean("home", true)) showHome();
        else showBrowser(app.page);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        ui.postDelayed(() -> {
            if (!app.hookReady) {
                status.setText(R.string.detector_unavailable);
                status.setVisibility(View.VISIBLE);
            }
        }, 10000);
    }
    private MaterialButton button(String text, View.OnClickListener listener) {
        MaterialButton b = new MaterialButton(this); b.setText(text); b.setTextSize(21); b.setAllCaps(false);
        b.setMinWidth(0); b.setInsetTop(0); b.setInsetBottom(0); b.setPadding(0,0,0,0);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tone(com.google.android.material.R.attr.colorSecondaryContainer)));
        b.setTextColor(tone(com.google.android.material.R.attr.colorOnSecondaryContainer));
        b.setCornerRadius(dp(24)); b.setOnClickListener(listener); return b;
    }
    private ImageView icon(int drawable, int colorAttribute) {
        ImageView image = new ImageView(this);
        image.setImageResource(drawable);
        image.setImageTintList(android.content.res.ColorStateList.valueOf(tone(colorAttribute)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private MaterialButton iconButton(int drawable, String description, View.OnClickListener listener) {
        MaterialButton control = button("", listener);
        control.setContentDescription(description);
        control.setIconResource(drawable);
        control.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        control.setIconPadding(0);
        control.setIconSize(dp(24));
        control.setIconTint(android.content.res.ColorStateList.valueOf(tone(com.google.android.material.R.attr.colorOnSurface)));
        control.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        return control;
    }
    private View bottomAction(LinearLayout row, int drawable, String label, View.OnClickListener listener) {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.VERTICAL);
        control.setGravity(Gravity.CENTER);
        control.setFocusable(true);
        control.setContentDescription(label);
        control.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            control.setBackgroundResource(ripple.resourceId);
        }
        control.setOnClickListener(listener);
        control.addView(icon(drawable, com.google.android.material.R.attr.colorOnSurface),
            new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setTextSize(12);
        caption.setTextColor(tone(com.google.android.material.R.attr.colorOnSurfaceVariant));
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2);
        captionParams.topMargin = dp(3);
        control.addView(caption, captionParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1);
        params.setMargins(dp(2), 0, dp(2), 0);
        row.addView(control, params);
        return control;
    }
    private void navigate() {
        navigate(address.getText().toString());
    }

    private void navigate(String text) {
        String input = text.trim();
        if (input.isEmpty()) return;
        if (input.regionMatches(true, 0, "https://", 0, 8) || input.regionMatches(true, 0, "http://", 0, 7)) {
            Uri uri = Uri.parse(input).normalizeScheme();
            if (uri.getHost() == null || uri.getHost().isEmpty() || input.chars().anyMatch(Character::isWhitespace)) {
                toast("Проверьте адрес страницы");
                return;
            }
            showBrowser(uri.toString());
        } else if (input.matches("(?i)(?:localhost|(?:[\\p{L}\\d-]+\\.)+[\\p{L}\\d-]+)(?::\\d+)?(?:[/\\?#][^\\s]*)?")) {
            showBrowser("https://" + input);
        } else {
            search(input);
        }
    }

    private void search(String input) {
        String query = input.trim();
        if (query.isEmpty()) return;
        library.rememberSearch(query);
        showBrowser(Uri.parse("https://www.google.com/search").buildUpon()
            .appendQueryParameter("q", query).build().toString());
    }

    private void showHome() {
        atHome = true;
        address.setText("");
        address.clearFocus();
        toolbar.setVisibility(View.GONE);
        browser.setVisibility(View.GONE);
        media.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        if (home != null) browserFrame.removeView(home);

        LinearLayout content = new LinearLayout(this);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        TextView title = titleText("Stream Browser", 28);
        title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.bottomMargin = dp(24);
        content.addView(title, titleParams);

        MaterialCardView field = new MaterialCardView(this);
        field.setRadius(dp(28));
        field.setCardElevation(0);
        field.setStrokeWidth(0);
        field.setCardBackgroundColor(tone(com.google.android.material.R.attr.colorSurfaceContainerHigh));
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), 0, dp(4), 0);
        searchRow.addView(icon(R.drawable.ms_search, com.google.android.material.R.attr.colorOnSurfaceVariant),
            new LinearLayout.LayoutParams(dp(22), dp(22)));
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Поиск в Google или адрес");
        query.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        query.setBackgroundColor(Color.TRANSPARENT);
        query.setTextSize(15);
        query.setPadding(dp(10), 0, 0, 0);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_GO) return false;
            navigate(query.getText().toString()); return true;
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0, -1, 1));
        searchRow.addView(iconButton(R.drawable.ms_arrow_forward, "Открыть адрес", v -> navigate(query.getText().toString())),
            new LinearLayout.LayoutParams(dp(48), dp(48)));
        field.addView(searchRow, new FrameLayout.LayoutParams(-1, -1));
        content.addView(field, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setGravity(Gravity.CENTER);
        shortcuts.setPadding(0, dp(14), 0, 0);
        homeShortcut(shortcuts, R.drawable.ms_history, "История", this::showSearchHistory);
        homeShortcut(shortcuts, R.drawable.ms_folder, "Папки", this::showFolders);
        content.addView(shortcuts, new LinearLayout.LayoutParams(-1, -2));
        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        home = scroll;
        browserFrame.addView(home, new FrameLayout.LayoutParams(-1, -1));
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        updateBack();
    }

    private void homeShortcut(LinearLayout row, int drawable, String label, Runnable action) {
        MaterialButton control = iconButton(drawable, label, v -> action.run());
        control.setText(label);
        control.setTextSize(13);
        control.setIconSize(dp(20));
        control.setIconPadding(dp(8));
        control.setPadding(dp(12), 0, dp(12), 0);
        row.addView(control, new LinearLayout.LayoutParams(-2, dp(48)));
    }

    private void showFolders() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout content = contentColumn();
        sheetTitle(content, "Папки закладок", sheet);
        for (String folder : library.folders()) {
            addCard(content, R.drawable.ms_folder, folder, bookmarkCount(folder), false, () -> {
                sheet.dismiss();
                showFolder(folder);
            });
        }
        addCard(content, R.drawable.ms_create_new_folder, "Новая папка", "Сохранённые страницы", false, () -> {
            sheet.dismiss();
            createFolder(folder -> showFolder(folder));
        });
        sheet.setContentView(scroll(content));
        sheet.show();
    }

    private String bookmarkCount(String folder) {
        int count = library.bookmarks(folder).size();
        return getResources().getQuantityString(R.plurals.bookmark_count, count, count);
    }

    private void showSearchHistory() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout content = contentColumn();
        sheetTitle(content, "История поиска", sheet);
        List<String> queries = library.history();
        for (String query : queries) {
            MaterialCardView card = addCard(content, R.drawable.ms_history, query, "Повторить поиск", false, () -> {
                sheet.dismiss();
                search(query);
            });
            card.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(this).setTitle("Удалить запрос?")
                    .setMessage(query).setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить", (dialog, which) -> {
                        library.forgetSearch(query);
                        sheet.dismiss();
                        showSearchHistory();
                    }).show();
                return true;
            });
        }
        if (queries.isEmpty()) {
            addCard(content, R.drawable.ms_history, "Запросов пока нет", "История хранится на этом устройстве.", false, null);
        } else {
            addCard(content, R.drawable.ms_delete, "Очистить историю", "Удалить сохранённые запросы", false, () ->
                new MaterialAlertDialogBuilder(this).setTitle("Очистить историю поиска?")
                    .setNegativeButton("Отмена", null).setPositiveButton("Очистить", (dialog, which) -> {
                        library.clearHistory();
                        sheet.dismiss();
                        if (atHome) showHome();
                    }).show());
        }
        sheet.setContentView(scroll(content));
        sheet.show();
    }

    private void createFolder(java.util.function.Consumer<String> onCreated) {
        TextInputLayout field = new TextInputLayout(this);
        field.setPadding(dp(24), dp(12), dp(24), 0);
        field.setHint("Название папки");
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setSingleLine();
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        field.addView(input);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("Новая папка").setView(field).setNegativeButton("Отмена", null)
            .setPositiveButton("Создать", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (!library.createFolder(name)) {
                field.setError(name.isEmpty() ? "Введите название" : "Папка с таким названием уже есть");
                return;
            }
            dialog.dismiss();
            onCreated.accept(name);
        }));
        dialog.show();
    }

    private void saveBookmark() {
        String url = app.page;
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        String title = pageTitle == null || pageTitle.trim().isEmpty() ? sourceAddress(url) : pageTitle;
        List<String> folders = library.folders();
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this).setTitle("Сохранить в папку");
        if (folders.isEmpty()) dialog.setMessage("Создайте папку для этой закладки.");
        else dialog.setItems(folders.toArray(new String[0]),
            (choice, which) -> storeBookmark(title, url, folders.get(which)));
        dialog.setNeutralButton("Новая папка", (choice, which) -> createFolder(folder -> storeBookmark(title, url, folder)))
            .setNegativeButton("Отмена", null).show();
    }

    private void storeBookmark(String title, String url, String folder) {
        try {
            library.addBookmark(new BrowserLibrary.Bookmark(title, url, folder));
            toast("Закладка сохранена");
        } catch (org.json.JSONException error) {
            toast("Не удалось сохранить закладку");
        }
    }

    private void showFolder(String folder) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout content = contentColumn();
        sheetTitle(content, folder, sheet);
        List<BrowserLibrary.Bookmark> bookmarks = library.bookmarks(folder);
        if (bookmarks.isEmpty()) {
            addCard(content, R.drawable.ms_folder, "Папка пуста",
                "Откройте страницу и выберите «Сохранить страницу» в меню браузера.", false, null);
        }
        for (BrowserLibrary.Bookmark bookmark : bookmarks) {
            MaterialCardView card = addCard(content, R.drawable.ms_link, bookmark.title(), sourceAddress(bookmark.url()),
                false, () -> { sheet.dismiss(); showBrowser(bookmark.url()); });
            card.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(this).setTitle("Удалить закладку?")
                    .setMessage(bookmark.title()).setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить", (dialog, which) -> {
                        library.removeBookmark(bookmark);
                        sheet.dismiss();
                        if (atHome) showHome();
                        showFolder(folder);
                    }).show();
                return true;
            });
        }
        MaterialButton delete = button("Удалить папку", v -> new MaterialAlertDialogBuilder(this)
            .setTitle("Удалить папку?")
            .setMessage("Закладки в папке тоже будут удалены.")
            .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (dialog, which) -> {
                library.removeFolder(folder);
                sheet.dismiss();
                showFolders();
            }).show());
        delete.setTextSize(14);
        content.addView(delete, new LinearLayout.LayoutParams(-1, dp(48)));
        sheet.setContentView(scroll(content));
        sheet.show();
    }

    private void showBrowser(String url) {
        View focused = getCurrentFocus();
        if (focused != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        atHome = false;
        if (home != null) home.setVisibility(View.GONE);
        toolbar.setVisibility(View.VISIBLE);
        browser.setVisibility(View.VISIBLE);
        media.setVisibility(View.VISIBLE);
        address.setText(url);
        address.clearFocus();
        browser.requestFocus();
        app.session.loadUri(url);
        updateBack();
    }

    private void back() {
        if (atHome) { finish(); return; }
        if (canGoBack) app.session.goBack();
        else showHome();
    }

    private void updateBack() {
        backButton.setEnabled(!atHome);
        backButton.setAlpha(atHome ? .45f : 1f);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("home", atHome);
        super.onSaveInstanceState(state);
    }
    private void updateMedia() {
        int count = app.currentMedia().size();
        media.setText(count == 0 ? "Медиа" : getString(R.string.media_count, count));
        if (app.hookReady || atHome) status.setVisibility(View.GONE);
        else if (status.getText().length() == 0) status.setVisibility(View.GONE);
        if (mediaList != null) renderMedia();
    }
    private void menu() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout list = contentColumn();
        sheetTitle(list, "Действия браузера", sheet);
        if (!atHome) {
            addCard(list, R.drawable.ms_video_library, "Медиа на странице",
                "Выбрать источник воспроизведения", false, () -> { sheet.dismiss(); showMedia(); });
            addCard(list, R.drawable.ms_bookmark_add, "Сохранить страницу",
                "Добавить закладку в папку", false, () -> { sheet.dismiss(); saveBookmark(); });
            addCard(list, R.drawable.ms_refresh, "Обновить страницу", sourceAddress(app.page), false,
                () -> { sheet.dismiss(); app.session.reload(); });
        }
        addCard(list, R.drawable.ms_history, "История поиска", "Недавние запросы Google", false,
            () -> { sheet.dismiss(); showSearchHistory(); });
        addCard(list, R.drawable.ms_link, "Скачать по ссылке",
            "Вставить прямой адрес файла", false, () -> {
                sheet.dismiss();
                enterFileUrl();
            });
        sheet.setContentView(scroll(list));
        sheet.show();
    }
    private void enterFileUrl() {
        TextInputLayout field = new TextInputLayout(this);
        field.setPadding(dp(24), dp(12), dp(24), 0);
        field.setHint("Адрес файла");
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setSingleLine();
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        field.addView(input);
        new MaterialAlertDialogBuilder(this).setTitle("Скачать по ссылке").setView(field)
            .setPositiveButton("Далее", (dialog, which) ->
                prepare(input.getText().toString().trim(), "Файл", null, null))
            .setNegativeButton("Отмена", null).show();
    }

    private void showMedia() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        mediaSheet = sheet;
        mediaList = contentColumn();
        renderMedia();
        sheet.setContentView(scroll(mediaList));
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        sheet.setOnDismissListener(dialog -> {
            mediaList = null;
            mediaSheet = null;
        });
        sheet.show();
    }

    private void renderMedia() {
        mediaList.removeAllViews();
        sheetTitle(mediaList, "Что скачать", mediaSheet);
        BrowserApp.Playback selection = app.playback;
        String title = selection == null || selection.title().isBlank()
            ? sourceAddress(app.page) : selection.title();
        String details = selection == null ? "Запустите видео, чтобы определить источник."
            : playbackSummary(selection);
        boolean audio = selection != null && selection.audio();
        addCard(mediaList, audio ? R.drawable.ms_graphic_eq : R.drawable.ms_video_library,
            audio ? "Сейчас играет аудио" : "Текущее видео", title + "\n" + details, true, null);
        section(mediaList, R.drawable.ms_arrow_downward, "Источник и результат");
        List<BrowserApp.Candidate> sources = app.currentMedia();
        for (BrowserApp.Candidate source : sources) {
            int symbol = source.mime().startsWith("audio/") ? R.drawable.ms_graphic_eq : R.drawable.ms_movie;
            addCard(mediaList, symbol, source.label(), sourceAddress(source.url()) + "\n" + resultDescription(source), false,
                () -> prepare(source.url(), source.label(), selection, source));
        }
        if (sources.isEmpty() && selection != null) {
            addCard(mediaList, R.drawable.ms_link, "Источник ещё не определён",
                "Можно попробовать загрузку по адресу страницы.", false, null);
        }
        addCard(mediaList, R.drawable.ms_language, "Загрузить по адресу страницы",
            sourceAddress(app.page) + "\nВыбор видео зависит от сайта.", false, () -> {
                if (selection == null || app.isCurrent(selection)) prepare(app.page, "Страница · yt-dlp", selection, null);
            });
    }

    private String playbackSummary(BrowserApp.Playback selection) {
        ArrayList<String> details = new ArrayList<>();
        if (selection.width() > 0 && selection.height() > 0) {
            details.add(selection.width() + " × " + selection.height() + " в плеере");
        }
        if (selection.duration() >= 0) {
            long seconds = (long) selection.duration();
            details.add(String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
        }
        return details.isEmpty() ? "Данные воспроизведения ещё загружаются" : String.join(" · ", details);
    }

    private String sourceAddress(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        String path = uri.getEncodedPath();
        return host == null ? "Адрес страницы недоступен" : host + (path == null ? "" : path);
    }

    private String resultDescription(BrowserApp.Candidate source) {
        if (source != null && source.kind() == 1) return "Результат · исходный файл";
        String format = getSharedPreferences("settings", 0).getString("format", "");
        String quality = format.contains("<=720") ? "до 720p"
            : format.contains("<=1080") ? "до 1080p" : "лучшее доступное качество";
        return "Результат · медиафайл, " + quality;
    }

    private LinearLayout contentColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(22), dp(20), dp(32));
        return column;
    }

    private void sheetTitle(LinearLayout list, String title, BottomSheetDialog sheet) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView text = titleText(title, 28);
        text.setAccessibilityHeading(true);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(iconButton(R.drawable.ms_close, "Закрыть", v -> sheet.dismiss()),
            new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        list.addView(row, params);
    }

    private void section(LinearLayout list, int drawable, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(8), dp(12));
        row.addView(icon(drawable, androidx.appcompat.R.attr.colorPrimary),
            new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView text = titleText(title, 15);
        text.setAccessibilityHeading(true);
        text.setPadding(dp(8), 0, 0, 0);
        row.addView(text);
        list.addView(row);
    }

    private View scroll(View content) {
        androidx.core.widget.NestedScrollView container = new androidx.core.widget.NestedScrollView(this);
        container.addView(content);
        return container;
    }

    private TextView titleText(String text, int size) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextSize(size);
        heading.setTextColor(tone(com.google.android.material.R.attr.colorOnSurface));
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        return heading;
    }

    private MaterialCardView addCard(LinearLayout list, int drawable, String title, String detail, boolean featured, Runnable action) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24));
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(tone(featured ? com.google.android.material.R.attr.colorSecondaryContainer
            : com.google.android.material.R.attr.colorSurfaceContainerHigh));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(12), dp(14));
        MaterialCardView tile = new MaterialCardView(this);
        tile.setRadius(dp(14));
        tile.setStrokeWidth(0);
        tile.setCardElevation(0);
        tile.setCardBackgroundColor(tone(featured ? androidx.appcompat.R.attr.colorPrimary
            : com.google.android.material.R.attr.colorPrimaryContainer));
        tile.addView(icon(drawable, featured ? com.google.android.material.R.attr.colorOnPrimary
            : com.google.android.material.R.attr.colorOnPrimaryContainer),
            new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
        row.addView(tile, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), 0, dp(4), 0);
        TextView name = titleText(title, 16);
        if (featured) name.setTextColor(tone(com.google.android.material.R.attr.colorOnSecondaryContainer));
        body.addView(name);
        TextView summary = new TextView(this);
        summary.setText(detail);
        summary.setTextSize(14);
        summary.setTextColor(tone(featured ? com.google.android.material.R.attr.colorOnSecondaryContainer
            : com.google.android.material.R.attr.colorOnSurfaceVariant));
        summary.setMaxLines(5);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setPadding(0, dp(6), 0, 0);
        body.addView(summary);
        row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            row.addView(icon(R.drawable.ms_chevron_right, com.google.android.material.R.attr.colorOnSurfaceVariant),
                new LinearLayout.LayoutParams(dp(22), dp(22)));
            card.setFocusable(true);
            card.setOnClickListener(v -> action.run());
        }
        card.addView(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        list.addView(card, params);
        return card;
    }

    private void prepare(String url, String label, BrowserApp.Playback selection, BrowserApp.Candidate source) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) { toast("Нужна HTTP(S)-страница"); return; }
        if (selection != null && !app.isCurrent(selection)) return;
        String page = app.page;
        String format = getSharedPreferences("settings", 0).getString("format", "bestvideo+bestaudio/best");
        String result = label.equals("Файл") ? "Результат · исходный файл" : resultDescription(source);
        new MaterialAlertDialogBuilder(this).setTitle("Начать загрузку?")
            .setMessage(label + "\n" + sourceAddress(url) + "\n" + result + "\n\n"
                + "Скачивайте только материалы, на сохранение которых у вас есть право. Если скачивание противоречит правилам сайта, вы принимаете ответственность за свои действия.")
            .setNegativeButton("Отмена", null).setPositiveButton("Принимаю · скачать", (d, w) -> {
                if (!sameSelection(page, selection)) return;
                if (!app.hookReady) { withoutSession(url, label, page, selection, format); return; }
                app.resolve(url, source == null ? "" : source.frame()).accept(bundle -> {
                    if (!sameSelection(page, selection)) return;
                    try { enqueue(new JSONObject(bundle.getString("json")), label, format); }
                    catch (Exception error) { showEnqueueError(error); return; }
                    toast("Добавлено в загрузки");
                }, error -> {
                    if (sameSelection(page, selection)) withoutSession(url, label, page, selection, format);
                });
            }).show();
    }
    private boolean sameSelection(String page, BrowserApp.Playback selection) {
        if (!isDestroyed() && !isFinishing() && app.page.equals(page)
            && (selection == null || app.isCurrent(selection))) return true;
        if (!isDestroyed() && !isFinishing()) toast("Видео сменилось. Выберите источник заново.");
        return false;
    }
    private void withoutSession(String url, String label, String page, BrowserApp.Playback selection, String format) {
        new MaterialAlertDialogBuilder(this).setTitle("Нет контекста сессии")
                        .setMessage("Обновите страницу для передачи cookies. Можно также попробовать загрузить публичный адрес без авторизации.")
                        .setNegativeButton("Отмена", null).setPositiveButton("Без авторизации", (x, y) -> {
                            if (!sameSelection(page, selection)) return;
                            try {
                                JSONObject job = new JSONObject();
                                job.put("url", url);
                                enqueue(job, label, format);
                            } catch (Exception error) { showEnqueueError(error); return; }
                            toast("Добавлено в загрузки");
                        }).show();
    }
    private void enqueue(JSONObject job, String label, String format) throws Exception {
        String id = UUID.randomUUID().toString();
        job.put("label", label); job.put("state", "В очереди");
        job.put("direct", label.equals("Файл"));
        job.put("format", format);
        SecureStore.save(this, id, job);
        startForegroundService(new Intent(this, DownloadService.class).putExtra("id", id));
    }
    private void showEnqueueError(Exception error) {
        android.util.Log.w("StreamDownload", "Queue creation failed: " + error.getClass().getSimpleName());
        toast("Не удалось запустить загрузку. Откройте список загрузок и попробуйте ещё раз.");
    }
    private void showDownloads() {
        File[] files = SecureStore.dir(this).listFiles((dir, name) -> name.endsWith(".bin"));
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout list = contentColumn();
        sheetTitle(list, "Загрузки", sheet);
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (File file : files) try {
                String id = file.getName().substring(0, file.getName().length() - 4);
                JSONObject job = SecureStore.read(this, id);
                boolean canResume = job.has("url");
                addCard(list, R.drawable.ms_download, job.optString("label"), job.optString("state")
                    + (canResume ? "\nНажмите, чтобы продолжить" : "\nDownloads/StreamBrowser"), false, canResume ? () -> {
                        startForegroundService(new Intent(this, DownloadService.class).putExtra("id", id));
                        sheet.dismiss();
                    } : null);
            } catch (Exception ignored) { }
        }
        if (list.getChildCount() == 1) {
            addCard(list, R.drawable.ms_download, "Загрузок пока нет", "Выберите источник в меню «Медиа» или вставьте ссылку на файл.", true, null);
        } else {
            list.addView(button("Остановить загрузку", v -> {
                startService(new Intent(this, DownloadService.class).setAction("STOP"));
                sheet.dismiss();
            }), new LinearLayout.LayoutParams(-1, dp(48)));
        }
        sheet.setContentView(scroll(list));
        sheet.show();
    }

    private void settings() {
        android.content.SharedPreferences preferences = getSharedPreferences("settings", 0);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout list = contentColumn();
        sheetTitle(list, "Настройки", sheet);
        String[] choices = {"Автоматически по устройству и сети", "2 соединения", "4 соединения", "8 соединений", "16 соединений"};
        int[] values = {0, 2, 4, 8, 16};
        int selected = Arrays.binarySearch(values, preferences.getInt("connections", 0));
        String[] qualities = {"Лучшее доступное", "До 1080p", "До 720p"};
        String[] formats = {"bestvideo+bestaudio/best", "bestvideo[height<=1080]+bestaudio/best[height<=1080]",
            "bestvideo[height<=720]+bestaudio/best[height<=720]"};
        int quality = Math.max(0, Arrays.asList(formats).indexOf(preferences.getString("format", "")));
        addCard(list, R.drawable.ms_download, "Параллельная загрузка", choices[Math.max(0, selected)], false, () -> {
            sheet.dismiss();
            new MaterialAlertDialogBuilder(this).setTitle("Параллельная загрузка")
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    preferences.edit().putInt("connections", values[which]).apply();
                    dialog.dismiss();
                    settings();
                }).setNegativeButton("Отмена", null).show();
        });
        addCard(list, R.drawable.ms_movie, "Качество видео", qualities[quality], false, () -> {
            sheet.dismiss();
            new MaterialAlertDialogBuilder(this).setTitle("Качество видео")
                .setSingleChoiceItems(qualities, quality, (dialog, which) -> {
                    preferences.edit().putString("format", formats[which]).apply();
                    dialog.dismiss();
                    settings();
                }).setNegativeButton("Отмена", null).show();
        });
        sheet.setContentView(scroll(list));
        sheet.show();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override public void onBackPressed() { back(); }
    @Override protected void onDestroy() {
        app.onMedia = () -> {}; ui.removeCallbacksAndMessages(null);
        browser.releaseSession(); app.session.setNavigationDelegate(null); app.session.setProgressDelegate(null);
        app.session.setContentDelegate(null);
        super.onDestroy();
    }
}
