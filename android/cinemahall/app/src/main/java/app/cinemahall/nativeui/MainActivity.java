package app.cinemahall.nativeui;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 12, 18);
    private static final int GOLD = Color.rgb(225, 184, 109);
    private static final int MUTED = Color.rgb(170, 176, 190);
    private final ExecutorService requests = Executors.newFixedThreadPool(2);
    private LinearLayout shelves;
    private ImageView backdrop;
    private TextView heroTitle, heroInfo;
    private boolean tv;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        tv = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildScreen();
        home("");
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private TextView text(String content, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(content); view.setTextSize(sp); view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private GradientDrawable panel(int color, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(13));
        if (stroke != 0) shape.setStroke(dp(2), stroke);
        return shape;
    }
    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(26), dp(18), dp(26), dp(14));
        TextView brand = text("CINEMA  /  HALL", tv ? 23 : 19, GOLD);
        brand.setLetterSpacing(.09f);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView search = text("⌕  Поиск", 17, Color.WHITE);
        search.setFocusable(true); search.setClickable(true);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(panel(0xFF232838, 0));
        search.setOnClickListener(v -> search());
        header.addView(search, new LinearLayout.LayoutParams(-2, dp(43)));
        column.addView(header);

        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF242B3A, 0xFF0C0E17}));
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View shadow = new View(this);
        shadow.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF101522, 0xE0101522, 0x15101522}));
        hero.addView(shadow, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(28), dp(20), dp(24), dp(20));
        TextView kicker = text("ВЫБИРАЙ, ЧТО СМОТРЕТЬ", 12, GOLD);
        kicker.setLetterSpacing(.13f);
        copy.addView(kicker);
        heroTitle = text("CinemaHall", tv ? 39 : 29, Color.WHITE);
        heroTitle.setMaxLines(2);
        copy.addView(heroTitle);
        heroInfo = text("Фильмы и сериалы из нашего каталога", 15, MUTED);
        copy.addView(heroInfo);
        FrameLayout.LayoutParams heroCopy = new FrameLayout.LayoutParams(
                tv ? dp(630) : -1, -2, Gravity.BOTTOM | Gravity.START);
        hero.addView(copy, heroCopy);
        column.addView(hero, new LinearLayout.LayoutParams(-1, dp(tv ? 320 : 220)));

        shelves = new LinearLayout(this);
        shelves.setOrientation(LinearLayout.VERTICAL);
        shelves.setPadding(dp(24), dp(24), dp(0), dp(40));
        column.addView(shelves);
        setContentView(scroll);
    }
    private void home(String query) {
        shelves.removeAllViews();
        if (query.isEmpty()) {
            shelf("Сейчас смотрят", "movie", "popular", "");
            shelf("Популярные сериалы", "series", "series", "");
            shelf("Новые фильмы", "movie", "new", "");
        } else {
            heroTitle.setText("Поиск: " + query);
            heroInfo.setText("Названия на разных языках");
            Artwork.into(backdrop, "", 1200, 700, true);
            shelf("Фильмы", "movie", "popular", query);
            shelf("Сериалы", "series", "series", query);
        }
    }
    private void shelf(String label, String type, String category, String search) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(label, tv ? 23 : 20, Color.WHITE);
        title.setPadding(0, 0, 0, dp(12));
        section.addView(title);
        HorizontalScrollView hscroll = new HorizontalScrollView(this);
        hscroll.setHorizontalScrollBarEnabled(false);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.HORIZONTAL);
        hscroll.addView(items);
        section.addView(hscroll);
        TextView progress = text("Загружаем подборку…", 14, MUTED);
        items.addView(progress);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, -2);
        sectionParams.bottomMargin = dp(28);
        shelves.addView(section, sectionParams);

        String extra = search.isEmpty() ? "" : "/search=" + Uri.encode(search);
        String path = "/catalog/" + type + "/" + category + extra + ".json";
        requests.execute(() -> {
            try {
                JSONArray cards = Api.get(this, path).getJSONArray("metas");
                runOnUiThread(() -> {
                    items.removeAllViews();
                    int count = Math.min(cards.length(), tv ? 18 : 12);
                    for (int i = 0; i < count; i++) {
                        JSONObject card = cards.optJSONObject(i);
                        if (card != null) items.addView(poster(card), posterParams());
                    }
                    if (count == 0) items.addView(text("Ничего не найдено", 15, MUTED));
                });
            } catch (Exception error) {
                runOnUiThread(() -> progress.setText("Источник недоступен. Повтори поиск позже."));
            }
        });
    }
    private LinearLayout.LayoutParams posterParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(tv ? 158 : 118), -2);
        params.rightMargin = dp(14);
        return params;
    }
    private View poster(JSONObject card) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(5), dp(5), dp(5), dp(7));
        item.setFocusable(true); item.setClickable(true);
        item.setBackground(panel(0xFF171C28, 0));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(panel(0xFF2A3040, 0));
        item.addView(image, new LinearLayout.LayoutParams(-1, dp(tv ? 214 : 163)));
        Artwork.into(image, card.optString("poster"), tv ? 158 : 118, tv ? 214 : 163, false);
        TextView name = text(card.optString("name", "Без названия"), 14, Color.WHITE);
        name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(43));
        nameParams.topMargin = dp(5);
        item.addView(name, nameParams);
        item.setOnFocusChangeListener((v, focused) -> {
            item.setBackground(panel(focused ? 0xFF2A3040 : 0xFF171C28, focused ? GOLD : 0));
            item.setScaleX(focused ? 1.035f : 1f);
            item.setScaleY(focused ? 1.035f : 1f);
            if (focused) preview(card);
        });
        item.setOnClickListener(v -> details(card));
        return item;
    }
    private void preview(JSONObject card) {
        heroTitle.setText(card.optString("name", "CinemaHall"));
        heroInfo.setText(card.optString("releaseInfo") + "     " + card.optString("imdbRating"));
        String background = card.optString("background");
        if (background.equals(card.optString("poster"))) background = "";
        // No background means a designed gradient, never a stretched portrait poster.
        Artwork.into(backdrop, background, tv ? 1280 : 720, tv ? 720 : 420, true);
    }
    private void search() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Название фильма или сериала");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Поиск по каталогу")
                .setView(input).setNegativeButton("Отмена", null)
                .setPositiveButton("Искать", (d, w) -> home(input.getText().toString().trim()))
                .show();
    }
    private Button button(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF323B50));
        return button;
    }
    private void details(JSONObject card) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(26), dp(20), dp(26), dp(32));
        scroll.addView(body);
        Button back = button("← Каталог");
        back.setOnClickListener(v -> dialog.dismiss());
        body.addView(back);
        TextView title = text(card.optString("name"), tv ? 35 : 27, Color.WHITE);
        title.setPadding(0, dp(18), 0, dp(10));
        body.addView(title);
        TextView summary = text("Загружаем описание и сезоны…", 16, MUTED);
        summary.setPadding(0, 0, 0, dp(14)); body.addView(summary);
        Button watch = button("Выбрать качество и озвучку");
        watch.setEnabled(false); body.addView(watch);
        Button episodes = button("Выбрать серию");
        episodes.setVisibility(View.GONE); body.addView(episodes);
        dialog.setContentView(scroll); dialog.show();

        String type = card.optString("type", "movie");
        String id = card.optString("id");
        requests.execute(() -> {
            try {
                JSONObject meta = Api.get(this, "/meta/" + type + "/" + Uri.encode(id) + ".json").getJSONObject("meta");
                runOnUiThread(() -> {
                    summary.setText(meta.optString("description", "Описание пока недоступно"));
                    JSONArray videos = meta.optJSONArray("videos");
                    if ("series".equals(type) && videos != null && videos.length() > 0) {
                        watch.setVisibility(View.GONE);
                        episodes.setVisibility(View.VISIBLE);
                        episodes.setOnClickListener(v -> chooseEpisode(meta, videos));
                    } else {
                        watch.setEnabled(true);
                        watch.setOnClickListener(v -> sources(meta, meta.optString("id")));
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> summary.setText("Не удалось загрузить данные тайтла"));
            }
        });
    }
    private void chooseEpisode(JSONObject meta, JSONArray videos) {
        int count = Math.min(videos.length(), 200);
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            JSONObject episode = videos.optJSONObject(i);
            names[i] = episode == null ? "Серия" : "Сезон " + episode.optInt("season") + " · Серия "
                    + episode.optInt("episode") + "  " + episode.optString("title");
        }
        new AlertDialog.Builder(this).setTitle("Сезоны и серии").setItems(names, (dialog, index) -> {
            JSONObject episode = videos.optJSONObject(index);
            if (episode != null) sources(meta, episode.optString("id"));
        }).show();
    }
    private void sources(JSONObject meta, String playbackId) {
        ProgressDialog loading = new ProgressDialog(this);
        loading.setMessage("Ищем доступные качества и озвучки…");
        loading.setCancelable(true); loading.show();
        String path = "/stream/" + meta.optString("type", "movie") + "/" + Uri.encode(playbackId) + ".json";
        requests.execute(() -> {
            try {
                JSONArray streams = Api.get(this, path).getJSONArray("streams");
                runOnUiThread(() -> {
                    loading.dismiss();
                    if (streams.length() == 0) message("Нет доступных источников");
                    else qualities(meta.optString("name"), streams);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { loading.dismiss(); message("Не получилось получить источники"); });
            }
        });
    }
    private void qualities(String title, JSONArray streams) {
        LinkedHashMap<String, ArrayList<JSONObject>> byQuality = new LinkedHashMap<>();
        for (String quality : new String[]{"2160p", "4K", "1440p", "2K", "1080p", "720p", "480p", "SD"}) {
            byQuality.put(quality, new ArrayList<>());
        }
        for (int i = 0; i < streams.length(); i++) {
            JSONObject stream = streams.optJSONObject(i);
            if (stream == null) continue;
            String quality = stream.optString("name").replace("CinemaHall · ", "").trim();
            byQuality.computeIfAbsent(quality, k -> new ArrayList<>()).add(stream);
        }
        byQuality.values().removeIf(List::isEmpty);
        if (byQuality.isEmpty()) { message("Нет доступных вариантов"); return; }

        Dialog picker = new Dialog(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(BG); content.setPadding(dp(20), dp(20), dp(20), dp(20));
        content.addView(text("Качество и озвучка", 24, Color.WHITE));
        HorizontalScrollView qscroll = new HorizontalScrollView(this);
        LinearLayout qrow = new LinearLayout(this); qrow.setOrientation(LinearLayout.HORIZONTAL);
        qscroll.addView(qrow); content.addView(qscroll);
        ScrollView vscroll = new ScrollView(this);
        LinearLayout voices = new LinearLayout(this); voices.setOrientation(LinearLayout.VERTICAL);
        vscroll.addView(voices); content.addView(vscroll, new LinearLayout.LayoutParams(-1, dp(tv ? 390 : 320)));
        for (Map.Entry<String, ArrayList<JSONObject>> quality : byQuality.entrySet()) {
            Button tab = button(quality.getKey());
            qrow.addView(tab);
            tab.setOnClickListener(v -> {
                voices.removeAllViews();
                for (JSONObject stream : quality.getValue()) {
                    String voice = stream.optString("title").split("\\n")[0];
                    Button choice = button(voice.isEmpty() ? "Озвучка не указана" : voice);
                    voices.addView(choice);
                    choice.setOnClickListener(click -> {
                        picker.dismiss();
                        Intent intent = new Intent(this, PlayerActivity.class);
                        intent.putExtra("url", stream.optString("url"));
                        intent.putExtra("title", title);
                        startActivity(intent);
                    });
                }
            });
        }
        picker.setContentView(content);
        picker.show();
        if (qrow.getChildCount() > 0) qrow.getChildAt(0).performClick();
    }
    private void message(String content) {
        new AlertDialog.Builder(this).setMessage(content).setPositiveButton("Понятно", null).show();
    }
    @Override protected void onDestroy() { requests.shutdownNow(); super.onDestroy(); }
}
