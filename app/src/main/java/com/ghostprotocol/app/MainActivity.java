package com.ghostprotocol.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.ghostprotocol.core.*;
import java.util.*;

/** Fully native, offline interface. No webview, backend, account or network permission. */
@SuppressLint("SetTextI18n") // The first version has an intentionally English interface.
public final class MainActivity extends Activity {
  private static final int BG = 0xff0b1110,
      PANEL = 0xff141d18,
      BORDER = 0xff2a382d,
      TEXT = 0xffedf2e8,
      MUTED = 0xff91a28f,
      LIME = 0xffc2f970,
      AMBER = 0xffe6b970;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private FrameLayout root;
  private Session session;
  private ScrollView scroll;
  private EditText nameInput;
  private String callsign = "", tab = "Signal", lastRender = "";
  private boolean roleRead, returningFromLocation;
  private boolean renderScheduled;
  private Runnable pendingRadio;
  private int insetTop, insetBottom;
  private AlertDialog privateDialog;
  private TextView liveTimer, calibrationCountdown, voteCountdown, cooldownCountdown;
  private ProgressBar liveProgress;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    callsign = getPreferences(MODE_PRIVATE).getString("callsign", "");
    root = new FrameLayout(this);
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            insetTop = insets.getInsets(WindowInsets.Type.systemBars()).top;
            insetBottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
          } else {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
          }
          root.setPadding(0, insetTop, 0, insetBottom);
          return insets;
        });
    setContentView(root);
    getWindow().setNavigationBarColor(BG);
    getWindow().setStatusBarColor(BG);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
              () -> {
                if (session != null) leave();
                else finish();
              });
    home();
  }

  private int d(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private TextView text(String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextColor(color);
    t.setTextSize(size);
    t.setFontFeatureSettings("kern");
    t.setLineSpacing(d(3), 1);
    return t;
  }

  private TextView mono(String value, int size, int color) {
    TextView t = text(value, size, color);
    t.setTypeface(Typeface.MONOSPACE);
    return t;
  }

  private TextView title(String value, int size) {
    TextView t = text(value, size, TEXT);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    t.setLineSpacing(0, 1);
    return t;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private void add(LinearLayout parent, View child) {
    parent.addView(child, new LinearLayout.LayoutParams(-1, -2));
  }

  private void space(LinearLayout parent, int height) {
    View v = new View(this);
    parent.addView(v, new LinearLayout.LayoutParams(1, d(height)));
  }

  private GradientDrawable shape(int fill, int stroke, int radius) {
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(fill);
    bg.setCornerRadius(d(radius));
    if (stroke != 0) bg.setStroke(d(1), stroke);
    return bg;
  }

  private LinearLayout card() {
    LinearLayout c = column();
    c.setPadding(d(18), d(18), d(18), d(18));
    c.setBackground(shape(PANEL, BORDER, 18));
    return c;
  }

  private Button button(String label, boolean primary, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(15);
    b.setAllCaps(false);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setTextColor(primary ? BG : TEXT);
    b.setBackground(shape(primary ? LIME : PANEL, primary ? 0 : BORDER, 14));
    b.setMinHeight(d(54));
    b.setPadding(d(14), d(8), d(14), d(8));
    b.setOnClickListener(v -> action.run());
    b.setStateListAnimator(null);
    return b;
  }

  private void fullButton(LinearLayout parent, String label, boolean primary, Runnable action) {
    Button b = button(label, primary, action);
    parent.addView(b, new LinearLayout.LayoutParams(-1, d(54)));
  }

  private void label(LinearLayout parent, String value) {
    TextView l = mono(value, 10, MUTED);
    l.setLetterSpacing(.12f);
    add(parent, l);
  }

  private void header(LinearLayout parent, String right, Runnable action) {
    LinearLayout r = row();
    ImageView mark = new ImageView(this);
    mark.setImageResource(com.ghostprotocol.app.R.drawable.ic_ghost);
    r.addView(mark, new LinearLayout.LayoutParams(d(35), d(35)));
    TextView name = mono("GHOST\nPROTOCOL", 12, TEXT);
    name.setLineSpacing(0, .93f);
    LinearLayout.LayoutParams n = new LinearLayout.LayoutParams(0, -2, 1);
    n.leftMargin = d(8);
    r.addView(name, n);
    TextView link = mono(right, 11, MUTED);
    link.setPadding(d(12), d(12), 0, d(12));
    if (action != null) {
      link.setOnClickListener(v -> action.run());
      link.setContentDescription(right);
    }
    r.addView(link);
    add(parent, r);
  }

  private LinearLayout mount(boolean tabs) {
    liveTimer = calibrationCountdown = voteCountdown = cooldownCountdown = null;
    liveProgress = null;
    int y = scroll == null ? 0 : scroll.getScrollY();
    root.removeAllViews();
    LinearLayout shell = column();
    root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = column();
    body.setPadding(d(24), d(14), d(24), d(24));
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    if (tabs) {
      LinearLayout nav = row();
      nav.setPadding(d(16), d(10), d(16), d(10));
      nav.setBackground(shape(BG, BORDER, 0));
      for (String item : List.of("Signal", "People", "Graph")) {
        TextView b = mono(item.toUpperCase(Locale.ROOT), 12, tab.equals(item) ? LIME : MUTED);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, d(13), 0, d(13));
        if (tab.equals(item)) b.setBackground(shape(PANEL, 0, 12));
        b.setOnClickListener(
            v -> {
              tab = item;
              lastRender = "";
              scroll.scrollTo(0, 0);
              render();
            });
        nav.addView(b, new LinearLayout.LayoutParams(0, d(46), 1));
      }
      add(shell, nav);
    }
    scroll.post(
        () -> {
          if (scroll != null) scroll.scrollTo(0, y);
        });
    return body;
  }

  private void home() {
    lastRender = "";
    scroll = null;
    LinearLayout body = mount(false);
    header(body, "LOCAL / 01", null);
    space(body, 36);
    label(body, "A GAME OF SIGNALS & SUSPICION");
    space(body, 12);
    add(body, title("Someone here\nis a ghost.", 42));
    space(body, 14);
    add(body, text("Read the room. Follow the interference.\nTrust carefully.", 16, MUTED));
    SignalView instrument = new SignalView(this);
    instrument.setSignal(1);
    body.addView(instrument, new LinearLayout.LayoutParams(-1, d(215)));
    space(body, 8);
    nameInput = new EditText(this);
    nameInput.setSingleLine(true);
    nameInput.setText(callsign);
    nameInput.setHint("Your callsign");
    nameInput.setHintTextColor(MUTED);
    nameInput.setTextColor(TEXT);
    nameInput.setTextSize(16);
    nameInput.setPadding(d(16), 0, d(16), 0);
    nameInput.setBackground(shape(PANEL, BORDER, 12));
    nameInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(24)});
    body.addView(nameInput, new LinearLayout.LayoutParams(-1, d(54)));
    space(body, 12);
    fullButton(
        body,
        "Create a room  ↗",
        true,
        () -> {
          if (saveName()) prepareRadio(() -> openSession(RoomCrypto.newCode(), true, false, false));
        });
    space(body, 10);
    fullButton(
        body,
        "Join a room",
        false,
        () -> {
          if (saveName()) joinDialog();
        });
    space(body, 18);
    TextView training = text("Try a training room →", 14, LIME);
    training.setGravity(Gravity.CENTER);
    training.setPadding(0, d(10), 0, d(10));
    training.setOnClickListener(
        v -> {
          if (saveName()) trainingDialog();
        });
    add(body, training);
    space(body, 24);
    TextView footer = mono("4–8 PLAYERS  ·  8 MINUTES  ·  1 GHOST", 10, MUTED);
    footer.setGravity(Gravity.CENTER);
    add(body, footer);
    space(body, 10);
    TextView offline = text("No internet. No accounts. Just this room.", 12, MUTED);
    offline.setGravity(Gravity.CENTER);
    add(body, offline);
  }

  private boolean saveName() {
    String n = nameInput.getText().toString().trim();
    if (n.isEmpty()) {
      nameInput.setError("Choose a callsign");
      return false;
    }
    callsign = n;
    getPreferences(MODE_PRIVATE).edit().putString("callsign", callsign).apply();
    return true;
  }

  private void joinDialog() {
    EditText code = new EditText(this);
    code.setTextColor(TEXT);
    code.setHintTextColor(MUTED);
    code.setHint("ABCDE-FGHIJ-KLMNO-PQRST");
    code.setSingleLine();
    code.setTextSize(14);
    code.setTypeface(Typeface.MONOSPACE);
    code.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    code.setPadding(d(24), d(12), d(24), d(12));
    code.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32)});
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Enter the room key")
            .setMessage(
                "Ask the room creator for their private 20-character key. All phones must be"
                    + " nearby.")
            .setView(code)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Join", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    w -> {
                      try {
                        new RoomCrypto(code.getText().toString());
                        String key = RoomCrypto.normalize(code.getText().toString());
                        dialog.dismiss();
                        prepareRadio(() -> openSession(key, false, false, false));
                      } catch (IllegalArgumentException e) {
                        code.setError(e.getMessage());
                      }
                    }));
    dialog.show();
  }

  private void trainingDialog() {
    new AlertDialog.Builder(this)
        .setTitle("Training room")
        .setMessage(
            "Play with five simulated players. Change your area to influence encounters and"
                + " signals. This mode uses no radios.")
        .setNegativeButton("Cancel", null)
        .setNeutralButton(
            "Play ghost", (v, w) -> openSession(RoomCrypto.newCode(), true, true, true))
        .setPositiveButton(
            "Play hunter", (v, w) -> openSession(RoomCrypto.newCode(), true, true, false))
        .show();
  }

  private void prepareRadio(Runnable next) {
    pendingRadio = next;
    List<String> needed = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 31)
      needed.addAll(
          List.of(
              Manifest.permission.BLUETOOTH_SCAN,
              Manifest.permission.BLUETOOTH_ADVERTISE,
              Manifest.permission.BLUETOOTH_CONNECT));
    needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
    needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
    needed.removeIf(p -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
    if (!needed.isEmpty()) {
      new AlertDialog.Builder(this)
          .setTitle("Connect the room")
          .setMessage(
              "Ghost Protocol uses Bluetooth and local proximity readings. Android requires Nearby"
                  + " devices and precise location permission for this. No location leaves these"
                  + " phones.")
          .setNegativeButton("Cancel", (v, w) -> pendingRadio = null)
          .setPositiveButton(
              "Continue", (v, w) -> requestPermissions(needed.toArray(new String[0]), 10))
          .show();
      return;
    }
    finishRadioSetup();
  }

  @SuppressWarnings("MissingPermission")
  private void finishRadioSetup() {
    BluetoothManager manager = getSystemService(BluetoothManager.class);
    BluetoothAdapter a = manager == null ? null : manager.getAdapter();
    if (a == null) {
      toast("Bluetooth LE is unavailable. You can use the training room.");
      pendingRadio = null;
      return;
    }
    if (!a.isEnabled()) {
      startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 11);
      return;
    }
    if (!getSystemService(LocationManager.class).isLocationEnabled()) {
      new AlertDialog.Builder(this)
          .setTitle("Enable local proximity")
          .setMessage(
              "Turn on Location in Android Settings so Bluetooth proximity scanning can work. The"
                  + " app does not use GPS.")
          .setNegativeButton("Cancel", (v, w) -> pendingRadio = null)
          .setPositiveButton(
              "Open Settings",
              (v, w) -> {
                returningFromLocation = true;
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
              })
          .show();
      return;
    }
    Runnable next = pendingRadio;
    pendingRadio = null;
    if (next != null) next.run();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 10) {
      boolean granted = results.length > 0;
      for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
      if (granted) finishRadioSetup();
      else {
        pendingRadio = null;
        new AlertDialog.Builder(this)
            .setTitle("Permissions are needed for nearby play")
            .setMessage(
                "Allow Nearby devices and precise location in app settings, then create or join the"
                    + " room again. Training remains available.")
            .setNegativeButton("Close", null)
            .setPositiveButton(
                "App settings",
                (v, w) ->
                    startActivity(
                        new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()))))
            .show();
      }
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 11) {
      if (result == RESULT_OK) finishRadioSetup();
      else pendingRadio = null;
    }
  }

  private void openSession(String code, boolean host, boolean training, boolean ghost) {
    if (session != null) session.close();
    roleRead = false;
    tab = "Signal";
    scroll = null;
    lastRender = "";
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    session =
        new Session(
            this,
            callsign,
            code,
            host,
            training,
            ghost,
            new Session.Listener() {
              @Override
              public void changed() {
                scheduleRender();
              }

              @Override
              public void message(String t) {
                toast(t);
              }
            });
    render();
  }

  private void scheduleRender() {
    if (renderScheduled) return;
    renderScheduled = true;
    handler.postDelayed(renderTask, 80);
  }

  private final Runnable renderTask =
      () -> {
        renderScheduled = false;
        render();
      };

  private void render() {
    if (session == null) return;
    Game g = session.game();
    updateClock(g);
    String signature =
        session.screen
            + "/"
            + tab
            + "/"
            + session.status
            + "/"
            + (g == null
                ? session.lobbyMembers().size()
                : g.interval()
                    + "/"
                    + g.observations.size()
                    + "/"
                    + (g.vote == null ? "" : g.vote.suspect + g.vote.endsAt + g.vote.ballots)
                    + "/"
                    + g.charges
                    + "/"
                    + g.phase
                    + g.cleared
                    + (g.cooldownUntil > g.elapsedMs))
            + "/"
            + session.synchronizing()
            + "/"
            + session.connectedLinks()
            + "/"
            + session.readyCount()
            + "/"
            + session.begun()
            + "/"
            + session.missing()
            + "/"
            + session.zone()
            + "/"
            + roleRead;
    if (signature.equals(lastRender)) return;
    lastRender = signature;
    switch (session.screen) {
      case LOBBY:
        lobby();
        break;
      case ROLE:
        role();
        break;
      case ROUND:
        round();
        break;
      case RESULTS:
        results();
        break;
    }
  }

  private void updateClock(Game g) {
    if (g == null) return;
    if (liveTimer != null) {
      String value = time(g.remainingMs());
      if (!value.contentEquals(liveTimer.getText())) liveTimer.setText(value);
      liveTimer.setTextColor(g.remainingMs() < 60000 ? AMBER : TEXT);
    }
    if (liveProgress != null) liveProgress.setProgress(g.remainingMs());
    if (calibrationCountdown != null)
      calibrationCountdown.setText(
          "CALIBRATION / " + Math.max(0, (30000 - g.elapsedMs + 999) / 1000) + "s");
    if (voteCountdown != null && g.vote != null)
      voteCountdown.setText(
          "ROOM ACCUSATION / " + Math.max(0, (g.vote.endsAt - g.elapsedMs + 999) / 1000) + "s");
    if (cooldownCountdown != null)
      cooldownCountdown.setText(
          Math.max(0, (g.cooldownUntil - g.elapsedMs + 999) / 1000)
              + " seconds until the next nomination.");
  }

  private void lobby() {
    LinearLayout body = mount(false);
    header(body, "LEAVE", this::leave);
    space(body, 34);
    label(body, "LOCAL SESSION");
    space(body, 10);
    add(body, title("Gather your\nsuspects.", 38));
    space(body, 16);
    add(
        body,
        text(
            "Keep every phone awake, with Bluetooth on. Share this key only with people playing.",
            15,
            MUTED));
    space(body, 24);
    LinearLayout key = card();
    label(key, "PRIVATE ROOM KEY");
    space(key, 12);
    TextView value = mono(RoomCrypto.format(session.code).replace('-', ' '), 18, LIME);
    value.setTextIsSelectable(true);
    add(key, value);
    space(key, 10);
    add(key, text("Hold to select and share verbally.", 11, MUTED));
    add(body, key);
    space(body, 24);
    label(body, "PHONES PRESENT / " + session.lobbyMembers().size() + " OF 8");
    space(body, 10);
    for (Session.Member m : session.lobbyMembers()) {
      LinearLayout r = row();
      TextView dot = text("●", 14, LIME);
      r.addView(dot);
      TextView n = text(m.name + (m.id.equals(session.self) ? "  (you)" : ""), 16, TEXT);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, d(44), 1);
      p.leftMargin = d(12);
      n.setGravity(Gravity.CENTER_VERTICAL);
      r.addView(n, p);
      add(body, r);
    }
    space(body, 12);
    add(body, text(session.status, 12, MUTED));
    space(body, 6);
    add(body, mono(session.connectedLinks() + " DIRECT LINKS · ENCRYPTED ROOM", 10, MUTED));
    space(body, 24);
    if (session.host) fullButton(body, "Assign roles & begin", true, session::startRound);
    else add(body, text("Waiting for the room creator to assign roles…", 15, LIME));
    space(body, 12);
    add(body, text("4–8 players · stay in the same room · walk, never run", 12, MUTED));
  }

  private void role() {
    LinearLayout body = mount(false);
    header(body, session.training ? "TRAINING" : "PRIVATE", null);
    space(body, 52);
    label(body, "THE PROTOCOL HAS CHOSEN");
    space(body, 12);
    add(body, title("Your identity\nis waiting.", 40));
    space(body, 18);
    add(
        body,
        text("Shield your screen. Read your role privately, then return to the room.", 16, MUTED));
    space(body, 24);
    SignalView s = new SignalView(this);
    s.setSignal(0);
    body.addView(s, new LinearLayout.LayoutParams(-1, d(225)));
    space(body, 20);
    fullButton(body, roleRead ? "Read my role again" : "Reveal my role", false, this::revealRole);
    space(body, 12);
    Button ready =
        button(
            "I've read it. Enter the hunt →",
            true,
            () -> {
              if (roleRead) session.ready();
              else toast("Read your role privately first.");
            });
    add(body, ready);
    space(body, 18);
    add(
        body,
        text(
            session.training
                ? "Training uses simulated encounters. Your room remains entirely on this phone."
                : "The timer starts after everyone has read their role.",
            12,
            MUTED));
  }

  private void revealRole() {
    boolean ghost = session.isGhost();
    privateDialog =
        new AlertDialog.Builder(this)
            .setTitle(ghost ? "YOU ARE THE GHOST" : "YOU ARE A HUNTER")
            .setMessage(
                ghost
                    ? "Blend into the room. Create misleading encounters. Survive eight minutes."
                        + " Your private tools let you plant an Echo or Fade your signal."
                    : "Move, compare signal history with encounters, and discuss your suspicions."
                        + " Tag a nearby suspect when shared evidence is ready. A wrong"
                        + " conviction costs 45 seconds.")
            .setPositiveButton(
                "Hide my role",
                (v, w) -> {
                  roleRead = true;
                  lastRender = "";
                  render();
                })
            .create();
    privateDialog.getWindow();
    privateDialog.setOnShowListener(
        v -> privateDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
    privateDialog.show();
  }

  private void round() {
    Game g = session.game();
    LinearLayout body = mount(true);
    header(body, session.training ? "TRAINING" : "LEAVE", this::leave);
    space(body, 26);
    LinearLayout top = row();
    LinearLayout left = column();
    label(left, session.training ? "SIMULATED ROOM" : "HUNT / LOCAL MESH");
    space(left, 6);
    add(
        left,
        text(
            g.phase == Game.Phase.CALIBRATING ? "Calibrating the room" : "Find the interference",
            13,
            MUTED));
    top.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
    TextView timer = mono(time(g.remainingMs()), 39, g.remainingMs() < 60000 ? AMBER : TEXT);
    liveTimer = timer;
    top.addView(timer);
    add(body, top);
    space(body, 14);
    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    liveProgress = progress;
    progress.setMax(480000);
    progress.setProgress(g.remainingMs());
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(LIME));
    progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(BORDER));
    body.addView(progress, new LinearLayout.LayoutParams(-1, d(3)));
    space(body, 20);
    if (!session.begun()) {
      banner(
          body,
          "WAITING FOR THE ROOM",
          session.readyCount() + " / " + g.players.size() + " players have read their role.",
          AMBER);
      return;
    }
    if (session.paused()) {
      List<String> names = new ArrayList<>();
      for (String id : session.missing()) names.add(g.name(id));
      banner(
          body,
          "ROOM PAUSED",
          "Waiting for "
              + String.join(", ", names)
              + ". The round ends without a winner if a phone stays away.",
          AMBER);
    } else if (session.synchronizing())
      banner(
          body,
          "SYNCING LOCAL STATE",
          "Waiting for every phone to agree. Accusations pause during synchronization.",
          MUTED);
    if (g.phase == Game.Phase.CALIBRATING) {
      LinearLayout calibration =
          banner(
              body,
              "CALIBRATION / " + Math.max(0, (30000 - g.elapsedMs + 999) / 1000) + "s",
              "Walk around normally. Your first signal readings are arriving. Tags unlock after"
                  + " calibration.",
              LIME);
      calibrationCountdown = (TextView) calibration.getChildAt(0);
    }
    if (g.vote != null) voteCard(body, g);
    switch (tab) {
      case "People":
        people(body, g);
        break;
      case "Graph":
        graph(body, g);
        break;
      default:
        signal(body, g);
    }
    if (session.training) {
      space(body, 16);
      fullButton(body, "Move area · " + session.zone() + " →", false, session::move);
      space(body, 8);
      add(
          body,
          text(
              "Moving changes your simulated proximity. Other players move and vote on their own.",
              12,
              MUTED));
    }
    space(body, 18);
    TextView tools = mono("PRIVATE ROLE TOOLS  ↗", 11, MUTED);
    tools.setPadding(0, d(12), 0, d(12));
    tools.setOnClickListener(v -> privateTools());
    add(body, tools);
  }

  private LinearLayout banner(LinearLayout body, String heading, String message, int color) {
    LinearLayout c = card();
    TextView l = mono(heading, 10, color);
    add(c, l);
    space(c, 8);
    add(c, text(message, 13, MUTED));
    add(body, c);
    space(body, 14);
    return c;
  }

  private void signal(LinearLayout body, Game g) {
    List<Game.Observation> history = g.history(session.self);
    int band = history.isEmpty() ? 0 : history.get(history.size() - 1).band();
    String[] bands = {"Faint trace", "Signal nearby", "Strong interference"};
    label(body, "SIGNAL / LAST COMPLETED INTERVAL");
    SignalView s = new SignalView(this);
    s.setSignal(band);
    body.addView(s, new LinearLayout.LayoutParams(-1, d(230)));
    space(body, 12);
    add(body, title(history.isEmpty() ? "Listening to the room…" : bands[band], 27));
    space(body, 10);
    add(
        body,
        text(
            "Clues are delayed and deliberately noisy. Compare what you felt with who was near"
                + " you.",
            14,
            MUTED));
    space(body, 26);
    label(body, "YOUR SIGNAL HISTORY / LAST 6");
    space(body, 12);
    if (history.isEmpty()) add(body, text("Your first reading arrives shortly.", 14, MUTED));
    List<Game.Observation> reverse = new ArrayList<>(history);
    Collections.reverse(reverse);
    for (Game.Observation o : reverse) {
      LinearLayout c = card();
      LinearLayout r = row();
      TextView b =
          mono(
              new String[] {"FAINT", "NEARBY", "STRONG"}[o.band()],
              11,
              o.band() == 2 ? LIME : MUTED);
      r.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
      r.addView(mono(time(o.atMs()), 11, MUTED));
      add(c, r);
      space(c, 7);
      List<String> names = new ArrayList<>();
      for (String id : o.nearby()) names.add(g.name(id));
      add(c, text(names.isEmpty() ? "No recent encounters" : String.join(" · ", names), 13, TEXT));
      add(body, c);
      space(body, 8);
    }
  }

  private void people(LinearLayout body, Game g) {
    label(body, "THE PEOPLE IN YOUR ORBIT");
    space(body, 8);
    add(body, title("Everyone is a suspect.", 27));
    space(body, 10);
    add(
        body,
        text(
            "A tag needs a recent encounter, two independent observers, and three signal intervals"
                + " in the last 90 seconds.",
            14,
            MUTED));
    space(body, 18);
    if (g.cooldownUntil > g.elapsedMs) {
      LinearLayout cooldown =
          banner(
              body,
              "TAGS COOLING DOWN",
              ((g.cooldownUntil - g.elapsedMs + 999) / 1000)
                  + " seconds until the next nomination.",
              AMBER);
      cooldownCountdown = (TextView) cooldown.getChildAt(2);
    }
    for (Game.Player p : g.players) {
      LinearLayout c = card();
      LinearLayout r = row();
      TextView avatar = mono(p.name().substring(0, 1).toUpperCase(Locale.ROOT), 19, LIME);
      avatar.setGravity(Gravity.CENTER);
      avatar.setBackground(shape(0xff243124, 0, 12));
      r.addView(avatar, new LinearLayout.LayoutParams(d(42), d(42)));
      LinearLayout info = column();
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
      ip.leftMargin = d(12);
      r.addView(info, ip);
      add(info, text(p.name() + (p.id().equals(session.self) ? " · you" : ""), 16, TEXT));
      Game.Evidence e = g.evidence(p.id());
      add(
          info,
          text(
              g.cleared.contains(p.id())
                  ? "Cleared by the room"
                  : Math.min(2, e.observers())
                      + "/2 observers · "
                      + Math.min(3, e.intervals())
                      + "/3 intervals",
              11,
              g.cleared.contains(p.id()) ? LIME : MUTED));
      if (!p.id().equals(session.self) && !g.cleared.contains(p.id())) {
        TextView tagButton = mono("TAG ↗", 11, e.eligible() ? LIME : MUTED);
        tagButton.setPadding(d(12), d(12), 0, d(12));
        tagButton.setOnClickListener(v -> tagDialog(p));
        r.addView(tagButton);
      }
      add(c, r);
      if (!p.id().equals(session.self)) {
        space(c, 10);
        add(
            c,
            mono(
                g.recentlyNear(session.self, p.id()) ? "RECENT ENCOUNTER" : "NO RECENT ENCOUNTER",
                9,
                MUTED));
      }
      add(body, c);
      space(body, 10);
    }
  }

  private void tagDialog(Game.Player p) {
    Game g = session.game();
    Game.Event event = Game.Event.tag(session.self, p.id());
    String error = g.copy().apply(event);
    AlertDialog.Builder b =
        new AlertDialog.Builder(this)
            .setTitle("Nominate " + p.name() + "?")
            .setMessage(
                error.isEmpty()
                    ? "This opens a private room vote. Conviction needs a majority. A wrong"
                        + " conviction clears them, costs 45 seconds, and gives the ghost a"
                        + " charge."
                    : error
                        + "\n\nEvidence: "
                        + g.evidence(p.id()).observers()
                        + " independent observers and "
                        + g.evidence(p.id()).intervals()
                        + " distinct intervals.")
            .setNegativeButton("Close", null);
    if (error.isEmpty()) b.setPositiveButton("Start accusation", (v, w) -> action(event));
    b.show();
  }

  private void graph(LinearLayout body, Game g) {
    label(body, "THE ROOM'S COLLECTIVE MEMORY");
    space(body, 8);
    add(body, title("Follow the connections.", 27));
    space(body, 10);
    add(
        body,
        text(
            "Lines connect people encountered during nearby or strong readings. Amber nodes have"
                + " enough evidence for a nomination.",
            14,
            MUTED));
    space(body, 12);
    SignalView view = new SignalView(this);
    view.setGraph(g, session.self);
    body.addView(view, new LinearLayout.LayoutParams(-1, d(300)));
    space(body, 16);
    banner(
        body,
        "EVIDENCE ISN'T CERTAINTY",
        "Crowds and ghost decoys can implicate innocent people. Talk before you tag.",
        AMBER);
    label(body, "ROLLING WINDOW / 90 SECONDS");
    space(body, 8);
    add(
        body,
        text(
            "Old observations expire. Keep moving to refresh the room's shared evidence.",
            13,
            MUTED));
  }

  private void voteCard(LinearLayout body, Game g) {
    LinearLayout c = card();
    label(c, "ROOM ACCUSATION / " + Math.max(0, (g.vote.endsAt - g.elapsedMs + 999) / 1000) + "s");
    voteCountdown = (TextView) c.getChildAt(0);
    space(c, 10);
    add(c, title("Is " + g.name(g.vote.suspect) + " the ghost?", 23));
    space(c, 8);
    add(
        c,
        text(
            g.name(g.vote.initiator)
                + " started a tag. Ballots are private; abstaining counts against conviction.",
            13,
            MUTED));
    space(c, 14);
    if (g.vote.suspect.equals(session.self))
      add(c, text("You have been nominated. The other players are voting.", 14, AMBER));
    else if (g.vote.ballots.containsKey(session.self))
      add(c, text("Your ballot is locked. Waiting for the room…", 14, LIME));
    else {
      LinearLayout r = row();
      Button yes = button("Confirm", true, () -> action(Game.Event.vote(session.self, true))),
          no = button("Reject", false, () -> action(Game.Event.vote(session.self, false)));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, d(48), 1);
      p.rightMargin = d(8);
      r.addView(yes, p);
      r.addView(no, new LinearLayout.LayoutParams(0, d(48), 1));
      add(c, r);
    }
    add(body, c);
    space(body, 16);
  }

  private void privateTools() {
    if (!session.isGhost()) {
      privateDialog =
          new AlertDialog.Builder(this)
              .setTitle("Hunter protocol")
              .setMessage(
                  "You have no ghost abilities. Build evidence through movement and conversation."
                      + " Use People to nominate a nearby suspect.")
              .setPositiveButton("Hide", null)
              .create();
      showPrivate();
      return;
    }
    Game g = session.game();
    String state =
        g.charges
            + " charge"
            + (g.charges == 1 ? "" : "s")
            + " remaining"
            + (g.abilityReadyAt > g.elapsedMs
                ? " · ready in " + ((g.abilityReadyAt - g.elapsedMs + 999) / 1000) + "s"
                : "");
    privateDialog =
        new AlertDialog.Builder(this)
            .setTitle("Ghost controls")
            .setMessage(
                state
                    + "\n\n"
                    + "Fade lowers your signal for 20 seconds. Echo makes a recently encountered"
                    + " player contribute misleading signals for 20 seconds. Abilities share a"
                    + " 30-second cooldown.")
            .setNegativeButton("Hide", null)
            .setNeutralButton("Echo…", (v, w) -> echoDialog())
            .setPositiveButton(
                "Use Fade", (v, w) -> action(Game.Event.ability(session.self, "FADE", "")))
            .create();
    showPrivate();
  }

  private void showPrivate() {
    privateDialog.setOnShowListener(
        v -> privateDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
    privateDialog.show();
  }

  private void echoDialog() {
    Game g = session.game();
    List<Game.Player> near = new ArrayList<>();
    for (Game.Player p : g.players)
      if (!p.id().equals(session.self) && g.recentlyNear(session.self, p.id())) near.add(p);
    if (near.isEmpty()) {
      toast("Approach another player before planting an Echo.");
      return;
    }
    String[] names = new String[near.size()];
    for (int i = 0; i < near.size(); i++) names[i] = near.get(i).name();
    privateDialog =
        new AlertDialog.Builder(this)
            .setTitle("Plant an Echo")
            .setItems(
                names, (v, w) -> action(Game.Event.ability(session.self, "ECHO", near.get(w).id())))
            .setNegativeButton("Hide", null)
            .create();
    showPrivate();
  }

  private void action(Game.Event event) {
    String error = session.act(event);
    if (!error.isEmpty()) toast(error);
    lastRender = "";
    render();
  }

  private void results() {
    Game g = session.game();
    LinearLayout body = mount(false);
    header(body, "DEBRIEF", null);
    space(body, 40);
    label(
        body,
        g.winner.equals("HUNTERS")
            ? "HUNTERS WIN"
            : g.winner.equals("GHOST") ? "GHOST WINS" : "NO WINNER");
    space(body, 14);
    add(
        body,
        title(
            g.winner.equals("HUNTERS")
                ? "Ghost\ncontained."
                : g.winner.equals("GHOST") ? "Into thin\nair." : "Protocol\ninterrupted.",
            44));
    space(body, 18);
    add(body, text(g.endReason, 16, MUTED));
    space(body, 28);
    LinearLayout reveal = card();
    label(reveal, "THE GHOST WAS");
    space(reveal, 10);
    add(reveal, title(g.name(g.ghost), 30));
    space(reveal, 10);
    add(
        reveal,
        mono(
            g.cleared.size() + " CLEARED · " + time(480000 - g.remainingMs()) + " ELAPSED",
            11,
            MUTED));
    add(body, reveal);
    space(body, 26);
    label(body, "ROUND TIMELINE");
    space(body, 14);
    for (String entry : g.timeline) {
      add(body, text(entry, 13, MUTED));
      space(body, 14);
    }
    space(body, 16);
    fullButton(body, "Back to the room entrance", true, this::closeSession);
  }

  private String time(int ms) {
    int seconds = (ms + 999) / 1000;
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
  }

  private void leave() {
    new AlertDialog.Builder(this)
        .setTitle("Leave this room?")
        .setMessage(
            "Other phones will pause while waiting for you. Leaving for too long ends the round"
                + " without a winner.")
        .setNegativeButton("Stay", null)
        .setPositiveButton("Leave", (v, w) -> closeSession())
        .show();
  }

  private void closeSession() {
    if (session != null) {
      session.close();
      session = null;
    }
    handler.removeCallbacks(renderTask);
    renderScheduled = false;
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    home();
  }

  // API 33+ uses the platform callback registered in onCreate; this is the API 28–32 fallback.
  @SuppressLint("GestureBackNavigation")
  @Override
  public void onBackPressed() {
    if (session != null) leave();
    else super.onBackPressed();
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (privateDialog != null && privateDialog.isShowing()) privateDialog.dismiss();
    if (session != null) session.suspend();
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (session != null) session.resume();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (returningFromLocation) {
      returningFromLocation = false;
      if (getSystemService(LocationManager.class).isLocationEnabled()) finishRadioSetup();
      else pendingRadio = null;
    }
  }

  @Override
  protected void onDestroy() {
    if (session != null) session.close();
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
