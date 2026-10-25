package com.ghostprotocol.app;

import static com.ghostprotocol.app.NativeUi.*;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.LocationManager;
import android.media.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.ghostprotocol.core.*;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Native keyed screens: live data updates preserve controls, gestures, focus, and scroll. */
@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private FrameLayout root, scannerLayer;
  private LinearLayout shell;
  private ScrollView scroll;
  private NativeUi ui, navUi, dialogUi;
  private TextView liveTimer, liveStatus;
  private ProgressBar alarmBar;
  private Session session;
  private String callsign = "", tab = "Mission", pageKey = "", notice = "", inputTaskId = "";
  private boolean roleRead,
      touching,
      touchRenderPending,
      renderScheduled,
      returningFromLocation,
      setupOpen,
      privateToolsOpen;
  private int guideStep = -1, guideProgress;
  private String guideFeedback = "";
  private final List<Integer> entered = new ArrayList<>();
  private final Set<String> watched = new HashSet<>();
  private final Map<String, String> availability = new HashMap<>();
  private Game availabilityGame;
  private int availabilityAt = -1;
  private final Set<Game.Station> setupVerified = EnumSet.noneOf(Game.Station.class);
  private AlertDialog privateDialog, dialog;
  private BooleanSupplier dialogValid = () -> true;
  private DecoratedBarcodeView scanner;
  private Runnable pendingRadio;
  private ToneGenerator tone;
  private int topInset, bottomInset;

  private record PageBinding(
      LinearLayout shell,
      ScrollView scroll,
      NativeUi ui,
      NativeUi nav,
      TextView timer,
      TextView status,
      ProgressBar alarm) {}

  private final Map<String, PageBinding> roundPages = new HashMap<>();

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    callsign = getPreferences(MODE_PRIVATE).getString("callsign", "");
    root = new FrameLayout(this);
    root.setBackgroundColor(BG);
    root.setFocusableInTouchMode(true);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets b =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            topInset = b.top;
            bottomInset = b.bottom;
          } else {
            topInset = insets.getSystemWindowInsetTop();
            bottomInset = insets.getSystemWindowInsetBottom();
          }
          root.setPadding(0, topInset, 0, bottomInset);
          return insets;
        });
    setContentView(root);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
    render();
  }

  private int d(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private String time(int ms) {
    int s = Math.max(0, (ms + 999) / 1000);
    return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
  }

  private int seconds(int until) {
    return Math.max(0, (until - session.game().elapsedMs + 999) / 1000);
  }

  private void scheduleRender() {
    if (renderScheduled) return;
    renderScheduled = true;
    handler.postDelayed(renderTask, 60);
  }

  private final Runnable renderTask =
      () -> {
        renderScheduled = false;
        render();
      };

  @Override
  public boolean dispatchTouchEvent(android.view.MotionEvent event) {
    if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) touching = true;
    boolean handled = super.dispatchTouchEvent(event);
    if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
        || event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
      touching = false;
      if (touchRenderPending) {
        touchRenderPending = false;
        handler.removeCallbacks(renderTask);
        renderScheduled = false;
        render();
      } else scheduleRender();
    }
    return handled;
  }

  private void page(String key, boolean navigation) {
    if (key.equals(pageKey)) return;
    if (pageKey.equals("home")) captureCallsign();
    hideKeyboard();
    pageKey = key;
    if (shell != null) root.removeView(shell);
    PageBinding cached = navigation ? roundPages.get(key) : null;
    if (cached != null) {
      shell = cached.shell();
      scroll = cached.scroll();
      ui = cached.ui();
      navUi = cached.nav();
      liveTimer = cached.timer();
      liveStatus = cached.status();
      alarmBar = cached.alarm();
      root.addView(shell, 0, new FrameLayout.LayoutParams(-1, -1));
      scroll.scrollTo(0, 0);
      root.requestFocus();
      return;
    }
    shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    root.addView(shell, 0, new FrameLayout.LayoutParams(-1, -1));
    liveTimer = null;
    liveStatus = null;
    alarmBar = null;
    if (navigation) {
      LinearLayout pinned = new LinearLayout(this);
      pinned.setOrientation(LinearLayout.VERTICAL);
      pinned.setPadding(d(22), d(12), d(22), d(10));
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      TextView brand = new TextView(this);
      brand.setText("GHOST PROTOCOL\nTHE HAUNTED HEIST");
      brand.setTextColor(TEAL);
      brand.setTextSize(11);
      brand.setTypeface(android.graphics.Typeface.MONOSPACE);
      row.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
      liveTimer = new TextView(this);
      liveTimer.setTextColor(TEXT);
      liveTimer.setTextSize(31);
      liveTimer.setTypeface(android.graphics.Typeface.MONOSPACE);
      row.addView(liveTimer);
      pinned.addView(row);
      liveStatus = new TextView(this);
      liveStatus.setTextColor(MUTED);
      liveStatus.setTextSize(12);
      liveStatus.setPadding(0, d(6), 0, d(6));
      liveStatus.setOnClickListener(
          v -> {
            if (session != null && session.game().vote != null) {
              tab = "Crew";
              render();
            }
          });
      pinned.addView(liveStatus);
      alarmBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      alarmBar.setMax(100);
      alarmBar.setProgressTintList(android.content.res.ColorStateList.valueOf(AMBER));
      alarmBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(BORDER));
      pinned.addView(alarmBar, new LinearLayout.LayoutParams(-1, d(3)));
      shell.addView(pinned);
    }
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(d(22), d(16), d(22), d(24));
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    ui = new NativeUi(this, content);
    if (navigation) {
      LinearLayout nav = new LinearLayout(this);
      nav.setOrientation(LinearLayout.VERTICAL);
      nav.setPadding(d(14), d(8), d(14), d(8));
      shell.addView(nav);
      navUi = new NativeUi(this, nav);
    }
    root.requestFocus();
    if (navigation)
      roundPages.put(
          key, new PageBinding(shell, scroll, ui, navUi, liveTimer, liveStatus, alarmBar));
  }

  private Node header(String right, Runnable action) {
    return row(
            "header",
            label("brand", "GHOST / PROTOCOL", TEAL).flex(),
            button("header.action", right, false, action).gap(0))
        .gap(22);
  }

  private void render() {
    Game g = session == null ? null : session.game();
    updateClock(g);
    if (touching) {
      touchRenderPending = true;
      return;
    }
    if (dialog != null && dialog.isShowing() && !dialogValid.getAsBoolean()) dialog.dismiss();
    if (g != null && g.phase == Game.Phase.ENDED) {
      closeScanner();
      dismissDialogs();
      setupOpen = false;
      guideStep = -1;
      session.screen = Session.Screen.RESULTS;
    }
    if (guideStep >= 0) {
      guide();
      return;
    }
    if (setupOpen) {
      setup();
      return;
    }
    if (session == null) {
      home();
      return;
    }
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
    if (privateToolsOpen && privateDialog != null && privateDialog.isShowing())
      updatePrivateTools();
  }

  private void updateClock(Game g) {
    if (g == null) return;
    if (liveTimer != null) {
      String value = time(g.remainingMs());
      if (!value.contentEquals(liveTimer.getText())) liveTimer.setText(value);
      liveTimer.setTextColor(g.remainingMs() < 60000 ? RED : TEXT);
    }
    if (liveStatus != null) {
      String state =
          session.paused()
              ? "ROOM PAUSED · waiting for missing phones"
              : session.sending()
                  ? "Sending · waiting for room confirmation"
                  : session.synchronizing()
                      ? "Synchronizing · waiting for every phone"
                      : g.vote != null
                          ? "VOTE OPEN · " + seconds(g.vote.endsAt) + "s · tap to review"
                          : g.exposed ? "SHOWDOWN · ghost exposed" : "HEIST LIVE";
      String text = g.delivered() + "/3 RELICS   ·   ALARM " + g.alarm + "/100\n" + state;
      if (!text.contentEquals(liveStatus.getText())) liveStatus.setText(text);
    }
    if (alarmBar != null) alarmBar.setProgress(g.alarm);
  }

  private void home() {
    page("home", false);
    ui.render(
        List.of(
            header("Settings", this::settings),
            label("edition", "OPERATION 02 / OFFLINE SOCIAL HEIST", AMBER),
            title("hero", "Steal the relics.\nTrust your crew.", 38),
            text(
                "intro",
                "One of you is the ghost. Recover three relics, seal them, and escape before the"
                    + " facility locks down.",
                16,
                MUTED),
            art("hero.art", "heist", 215),
            input("callsign", callsign),
            button(
                "play",
                "Play Heist",
                true,
                () -> {
                  if (saveName()) playDialog();
                }),
            button(
                "learn",
                "Learn by Doing · 90 seconds",
                false,
                () -> {
                  guideStep = 0;
                  guideProgress = 0;
                  guideFeedback = "";
                  render();
                }),
            row(
                "secondary",
                button("practice", "Practice", false, this::trainingDialog).flex(),
                button(
                        "kit",
                        "Room Kit",
                        false,
                        () -> {
                          setupOpen = true;
                          render();
                        })
                    .flex()),
            label("details", "4–8 PLAYERS  /  10 MINUTES  /  NO INTERNET", MUTED)));
  }

  private boolean saveName() {
    View view = ui.view("callsign");
    if (view instanceof EditText e) callsign = e.getText().toString().trim();
    if (callsign.isEmpty()) {
      if (view instanceof EditText e) e.setError("Choose a callsign");
      return false;
    }
    getPreferences(MODE_PRIVATE).edit().putString("callsign", callsign).apply();
    hideKeyboard();
    return true;
  }

  private void captureCallsign() {
    if (ui != null && ui.view("callsign") instanceof EditText input) {
      callsign = input.getText().toString().trim();
      getPreferences(MODE_PRIVATE).edit().putString("callsign", callsign).apply();
    }
  }

  private void hideKeyboard() {
    android.view.inputmethod.InputMethodManager manager =
        getSystemService(android.view.inputmethod.InputMethodManager.class);
    if (manager != null) manager.hideSoftInputFromWindow(root.getWindowToken(), 0);
    root.clearFocus();
  }

  private void playDialog() {
    new AlertDialog.Builder(this)
        .setTitle("Start an operation")
        .setMessage(
            "Put the three checkpoint cards around the room. Everyone installs version 2.0 and"
                + " keeps the game open.")
        .setPositiveButton(
            "Create a heist",
            (v, w) -> prepareRadio(() -> openSession(RoomCrypto.newCode(), true, false, false)))
        .setNeutralButton("Join a heist", (v, w) -> joinDialog())
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void joinDialog() {
    EditText key = new EditText(this);
    key.setSingleLine(true);
    key.setHint("20-character room key");
    key.setPadding(d(24), d(12), d(24), d(12));
    AlertDialog join =
        new AlertDialog.Builder(this)
            .setTitle("Join the crew")
            .setMessage("Enter the creator's private room key. Bluetooth and Location must be on.")
            .setView(key)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Join", null)
            .create();
    join.setOnShowListener(
        v ->
            join.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    w -> {
                      try {
                        new RoomCrypto(key.getText().toString());
                        String code = RoomCrypto.normalize(key.getText().toString());
                        join.dismiss();
                        prepareRadio(() -> openSession(code, false, false, false));
                      } catch (IllegalArgumentException e) {
                        key.setError(e.getMessage());
                      }
                    }));
    join.show();
  }

  private void trainingDialog() {
    if (session == null && ui != null && ui.view("callsign") instanceof EditText e) {
      callsign = e.getText().toString().trim();
      if (callsign.isEmpty()) callsign = "Agent";
    }
    if (callsign.isEmpty()) callsign = "Agent";
    new AlertDialog.Builder(this)
        .setTitle("Practice the heist")
        .setMessage(
            "Use virtual checkpoints and simulated partners. Practice is entirely on this phone; it"
                + " does not test Bluetooth.")
        .setPositiveButton(
            "Play hunter",
            (v, w) -> {
              guideStep = -1;
              openSession(RoomCrypto.newCode(), true, true, false);
            })
        .setNeutralButton(
            "Play ghost",
            (v, w) -> {
              guideStep = -1;
              openSession(RoomCrypto.newCode(), true, true, true);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void setup() {
    page("setup", false);
    List<Node> nodes = new ArrayList<>();
    nodes.add(
        header(
            "Back",
            () -> {
              setupOpen = false;
              render();
            }));
    nodes.add(label("kit.label", "PHYSICAL ROOM KIT", AMBER));
    nodes.add(title("kit.title", "Give the heist\na place to happen.", 34));
    nodes.add(
        text(
            "kit.info",
            "Place Vault, Workshop, and Exit cards at three distinct points. Leave room to walk"
                + " between them. Print these cards or display them on spare screens.",
            15,
            MUTED));
    Set<Game.Station> verified = session == null ? setupVerified : session.verifiedCheckpoints;
    for (Game.Station station : Game.Station.values())
      if (station != Game.Station.NONE) {
        nodes.add(
            card(
                "kit." + station,
                title("kit.name." + station, HuntIntel.title(station.name()), 23),
                label(
                    "kit.check." + station,
                    verified.contains(station) ? "PLACED & VERIFIED" : "PLACE AND SCAN THIS CARD",
                    verified.contains(station) ? TEAL : MUTED),
                button("kit.show." + station, "Show QR card", false, () -> showCard(station))));
      }
    nodes.add(
        button("kit.print", "Print / save checkpoint PDF", true, () -> CheckpointKit.print(this)));
    nodes.add(button("kit.scan", "Scan a placed checkpoint", false, this::startScanner));
    nodes.add(
        text(
            "kit.tip",
            "Cards contain only a checkpoint label. Keep the room key private. Walk during play;"
                + " never run.",
            13,
            MUTED));
    ui.render(nodes);
  }

  private void showCard(Game.Station station) {
    Bitmap bitmap = CheckpointKit.qr(station, 720);
    ImageView image = new ImageView(this);
    image.setImageBitmap(bitmap);
    image.setAdjustViewBounds(true);
    image.setBackgroundColor(android.graphics.Color.WHITE);
    image.setPadding(d(18), d(18), d(18), d(18));
    AlertDialog card =
        new AlertDialog.Builder(this)
            .setTitle(station.name() + " checkpoint")
            .setView(image)
            .setPositiveButton("Close", null)
            .create();
    card.setOnDismissListener(v -> bitmap.recycle());
    card.show();
  }

  private void lobby() {
    page("lobby", false);
    List<Node> nodes = new ArrayList<>();
    nodes.add(header("Leave", this::leave));
    nodes.add(label("lobby.label", "ASSEMBLE THE CREW", AMBER));
    nodes.add(title("lobby.title", "The operation\nstarts here.", 36));
    nodes.add(
        text(
            "lobby.info",
            "Share the key with your players. Keep every phone awake with Bluetooth and Location"
                + " enabled.",
            15,
            MUTED));
    nodes.add(
        card(
            "room.key",
            label("room.key.label", "PRIVATE ROOM KEY", MUTED),
            title("room.key.value", RoomCrypto.format(session.code), 21)));
    nodes.add(
        card(
            "setup.status",
            title("setup.title", "Room checkpoints", 22),
            text(
                "setup.count",
                session.verifiedCheckpoints.size() + " / 3 cards verified on this phone",
                14,
                MUTED),
            button(
                "setup.open",
                "Set up / verify room",
                false,
                () -> {
                  setupOpen = true;
                  render();
                })));
    nodes.add(
        label(
            "members.count", "PHONES PRESENT / " + session.lobbyMembers().size() + " OF 8", TEAL));
    for (Session.Member member : session.lobbyMembers())
      nodes.add(
          card(
              "member." + member.id,
              text(
                  "member.name." + member.id,
                  member.name + (member.id.equals(session.self) ? " · you" : ""),
                  18,
                  TEXT)));
    nodes.add(text("radio.status", session.status, 13, MUTED));
    nodes.add(
        label("radio.links", session.connectedLinks() + " DIRECT LINKS · ENCRYPTED ROOM", MUTED));
    if (session.host)
      nodes.add(
          button("begin", "Assign roles & begin", true, session::startRound)
              .enabled(
                  session.lobbyMembers().size() >= 4
                      && session.lobbyMembers().size() <= 8
                      && session.verifiedCheckpoints.size() == 3));
    else
      nodes.add(
          text(
              "waiting",
              "Waiting for the creator to verify checkpoints and assign roles.",
              15,
              AMBER));
    if (!notice.isEmpty()) nodes.add(text("notice", notice, 14, AMBER));
    ui.render(nodes);
  }

  private void role() {
    page("role", false);
    Game g = session.game();
    ui.render(
        List.of(
            header(session.training ? "Practice" : "Private", this::leave),
            label("role.label", "SHIELD YOUR SCREEN", AMBER),
            title("role.title", "Your identity\nis waiting.", 39),
            text(
                "role.intro",
                "Your public specialty is "
                    + HuntIntel.title(g.agents.get(session.self).specialty.name())
                    + ". Your allegiance is private.",
                16,
                MUTED),
            art("role.art", "heist", 210),
            button(
                "role.reveal",
                roleRead ? "Read my role again" : "Reveal my role",
                false,
                this::revealRole),
            button(
                    "role.ready",
                    "I've read it. Enter the heist →",
                    true,
                    () -> {
                      session.ready();
                      render();
                    })
                .enabled(roleRead),
            text(
                "role.wait",
                "The ten-minute clock starts when everyone is ready. Recovery → Workshop → Exit.",
                14,
                MUTED)));
  }

  private void revealRole() {
    privateDialog =
        new AlertDialog.Builder(this)
            .setTitle(session.isGhost() ? "YOU ARE THE GHOST" : "YOU ARE A HUNTER")
            .setMessage(
                session.isGhost()
                    ? "Pretend to help the heist. Secretly jam checkpoints and corrupt sealed"
                        + " relics. Win when Alarm reaches 100 or time runs out. If exposed, use"
                        + " Haunt and keep fighting during the showdown."
                    : "Recover three relics with a partner, seal them at the Workshop, and deliver"
                        + " them to the Exit. Solve the final puzzle together. Exposing the ghost"
                        + " helps, but the heist must still be completed.")
            .setPositiveButton(
                "Hide my role",
                (v, w) -> {
                  roleRead = true;
                  render();
                })
            .create();
    showSecure(privateDialog);
  }

  private void round() {
    Game g = session.game();
    page("round/" + tab, true);
    updateClock(g);
    navUi.render(List.of(row("nav", nav("Mission"), nav("Crew"), nav("Gear"))));
    List<Node> nodes = new ArrayList<>();
    nodes.add(header(session.training ? "Practice · leave" : "Leave", this::leave));
    if (!session.begun()) {
      nodes.add(
          card(
              "waiting",
              title("waiting.title", "Waiting for the crew", 26),
              text(
                  "waiting.info",
                  session.readyCount()
                      + " / "
                      + g.players.size()
                      + " players have read their role.",
                  15,
                  MUTED)));
      ui.render(nodes);
      return;
    }
    if (session.paused())
      nodes.add(
          card(
              "pause",
              title("pause.title", "Room paused", 25),
              text(
                  "pause.info",
                  "Missing: "
                      + missingNames()
                      + ". Keep the game open and restore Bluetooth. The heist ends without a"
                      + " winner after a prolonged absence.",
                  14,
                  AMBER)));
    if (!notice.isEmpty()) nodes.add(text("notice", notice, 14, AMBER));
    if (g.vote != null) nodes.add(voteCard(g));
    if (g.eventUntil > g.elapsedMs)
      nodes.add(
          card(
              "event",
              label("event.name", g.roomEvent + " / " + seconds(g.eventUntil) + "s", AMBER),
              text("event.info", eventDescription(g), 14, MUTED)));
    switch (tab) {
      case "Crew":
        crew(nodes, g);
        break;
      case "Gear":
        gear(nodes, g);
        break;
      default:
        mission(nodes, g);
    }
    nodes.add(
        row(
            "round.help",
            button(
                    "fieldguide",
                    "Field Guide",
                    false,
                    () -> {
                      guideStep = 0;
                      guideProgress = 0;
                      guideFeedback = "";
                      render();
                    })
                .flex(),
            button("briefing", "Private briefing", false, this::privateTools).flex()));
    ui.render(nodes);
    updateClock(g);
  }

  private Node nav(String value) {
    Node n =
        button(
                "nav." + value,
                value,
                tab.equals(value),
                () -> {
                  tab = value;
                  render();
                })
            .flex()
            .gap(0);
    return n;
  }

  private String missingNames() {
    List<String> names = new ArrayList<>();
    for (String id : session.missing()) names.add(session.game().name(id));
    return String.join(", ", names);
  }

  private String eventDescription(Game g) {
    return g.roomEvent.equals("BLACKOUT")
        ? "Signal clues are hidden. Checkpoints and relic actions still work."
        : g.roomEvent.equals("OVERCLOCK")
            ? "New paired puzzles have an extra hint and 45 seconds."
            : HuntIntel.title(g.lockedStation.name())
                + " is temporarily closed to new tasks. Existing puzzles continue.";
  }

  private void mission(List<Node> nodes, Game g) {
    HuntIntel.Objective objective = HuntIntel.objective(g, session.self);
    boolean openGhost = g.exposed && session.isGhost();
    nodes.add(
        card(
            "objective",
            label(
                "objective.step",
                openGhost ? "SHOWDOWN / STOP EXTRACTION" : objective.step(),
                TEAL),
            title(
                "objective.title",
                openGhost ? "The crew knows.\nMake them run out of time." : objective.title(),
                28),
            text(
                "objective.detail",
                openGhost
                    ? "Open your private briefing to jam any checkpoint or haunt a nearby hunter."
                    : objective.detail(),
                15,
                MUTED)));
    if (openGhost) {
      nodes.add(button("showdown.tools", "Open showdown controls", true, this::privateTools));
      nodes.add(
          text(
              "showdown.info",
              "Your carried relics returned to Workshop storage. Every player remains in the game.",
              15,
              MUTED));
      return;
    }
    Game.Task task = g.taskFor(session.self);
    if (task != null) {
      taskCard(nodes, g, task);
      return;
    }
    if (session.training) {
      nodes.add(label("practice.label", "PRACTICE / VIRTUAL CHECKPOINTS", AMBER));
      nodes.add(
          row(
              "practice.stations",
              practiceStation(Game.Station.VAULT),
              practiceStation(Game.Station.WORKSHOP),
              practiceStation(Game.Station.EXIT)));
      nodes.add(
          text(
              "practice.info",
              "Tap a checkpoint to simulate walking there. A practice partner joins your tasks and"
                  + " reports the sequence.",
              13,
              MUTED));
    } else
      nodes.add(
          button("scan", "Scan a checkpoint", true, this::startScanner)
              .enabled(!session.sending()));
    Game.Agent agent = g.agents.get(session.self);
    nodes.add(
        label(
            "visit",
            agent.station == Game.Station.NONE
                ? "NO CHECKPOINT SCANNED"
                : "LAST CHECKPOINT / "
                    + agent.station
                    + (g.visited(session.self, agent.station)
                        ? " · VERIFIED"
                        : " · SCAN AGAIN TO ACT"),
            MUTED));
    Game.Seal seal = g.seals.get(session.self);
    if (seal != null) {
      nodes.add(
          card(
              "sealing",
              title("sealing.title", "Securing your relic…", 25),
              text(
                  "sealing.time",
                  seconds(seal.finishesAt()) + " seconds · stay at the Workshop",
                  15,
                  TEAL),
              button(
                  "sealing.cancel",
                  "Cancel sealing",
                  false,
                  () -> action(Game.Event.cancel(session.self)))));
      return;
    }
    for (Game.Station station :
        List.of(Game.Station.VAULT, Game.Station.WORKSHOP, Game.Station.EXIT)) {
      Node stationCard =
          card(
              "station." + station,
              label(
                  "station.state." + station,
                  g.stationOpen(station) ? "CHECKPOINT ONLINE" : "CHECKPOINT DISABLED",
                  g.stationOpen(station) ? TEAL : RED),
              title("station.name." + station, HuntIntel.title(station.name()), 25));
      boolean present = g.visited(session.self, station);
      if (!g.stationOpen(station)) {
        stationCard.add(
            text(
                "station.jam." + station,
                "Repair a ghost jam with a partner, or wait for the checkpoint to recover.",
                13,
                MUTED));
        if (g.jamUntil.getOrDefault(station, 0) > g.elapsedMs)
          stationCard.add(
              actButton(
                  "repair." + station,
                  "Start repair",
                  Game.Event.task(session.self, "REPAIR", ""),
                  present));
      }
      for (Game.Task waiting : g.tasks.values())
        if (waiting.station == station && !waiting.active())
          stationCard.add(
              actButton(
                  "join." + waiting.id,
                  "Join " + g.name(waiting.operator),
                  Game.Event.join(session.self, waiting.id),
                  present));
      if (station == Game.Station.VAULT) {
        stationCard.add(
            text("vault.info", "Two people. One sequence. Recover a relic together.", 14, MUTED));
        stationCard.add(
            actButton(
                "breach",
                "Start Vault breach",
                Game.Event.task(session.self, "BREACH", ""),
                present && g.stationOpen(station)));
        if (session.training)
          stationCard.add(
              button(
                      "practice.reader",
                      "Let a practice partner lead",
                      false,
                      session::practicePartnerLeads)
                  .enabled(present && g.stationOpen(station)));
        for (Game.Relic r : g.relics.values())
          if (!r.owner.isEmpty() && g.elapsedMs - r.heldAt >= 60000)
            stationCard.add(
                actButton(
                    "recall." + r.id,
                    "Recall " + r.name + " · Alarm +10",
                    Game.Event.task(session.self, "RECALL", r.id),
                    present && g.stationOpen(station)));
      }
      if (station == Game.Station.WORKSHOP) {
        stationCard.add(
            text(
                "workshop.info",
                "Seal carried relics, collect stored loot, or purge Alarm together.",
                14,
                MUTED));
        for (Game.Relic r : g.inventory(session.self))
          if (r.state == Game.RelicState.RAW)
            stationCard.add(
                actButton(
                    "seal." + r.id,
                    "Seal " + r.name + " · 6s",
                    Game.Event.seal(session.self, r.id),
                    present && g.stationOpen(station)));
        for (Game.Relic r : g.relics.values())
          if (r.owner.isEmpty() && r.location == station)
            stationCard.add(
                actButton(
                    "take." + r.id,
                    "Collect " + r.name,
                    Game.Event.take(session.self, r.id),
                    present && g.stationOpen(station)));
        stationCard.add(
            actButton(
                "purge",
                "Purge Alarm −15",
                Game.Event.task(session.self, "PURGE", ""),
                present && g.stationOpen(station) && g.alarm > 0 && g.purgeReadyAt <= g.elapsedMs));
      }
      if (station == Game.Station.EXIT) {
        stationCard.add(
            text(
                "exit.info",
                g.delivered()
                    + " / 3 relics delivered. Once all arrive, solve the final puzzle with a"
                    + " partner.",
                14,
                MUTED));
        for (Game.Relic r : g.inventory(session.self))
          if (r.state == Game.RelicState.SEALED)
            stationCard.add(
                actButton(
                    "deposit." + r.id,
                    "Deposit " + r.name,
                    Game.Event.deposit(session.self, r.id),
                    present && g.stationOpen(station)));
        stationCard.add(
            actButton(
                "extract",
                "Open the Exit together",
                Game.Event.task(session.self, "EXTRACT", ""),
                present && g.stationOpen(station) && g.delivered() == 3));
      }
      if (!present)
        stationCard.add(
            text(
                "station.reason." + station,
                "Scan this checkpoint to use its controls.",
                12,
                MUTED));
      nodes.add(stationCard);
    }
    List<Game.Entry> feed = g.visibleEvents("", false);
    Node activity = card("activity", label("activity.label", "OPERATION FEED", MUTED));
    for (int n = Math.max(0, feed.size() - 3); n < feed.size(); n++) {
      Game.Entry e = feed.get(n);
      activity.add(text("event." + n, time(e.atMs()) + " · " + e.detail(), 13, MUTED));
    }
    nodes.add(activity);
  }

  private Node practiceStation(Game.Station station) {
    return button(
            "practice." + station,
            HuntIntel.title(station.name()),
            false,
            () -> {
              String error = session.scanStation(station);
              if (!error.isEmpty()) message(error);
              render();
            })
        .flex();
  }

  private Node actButton(String key, String value, Game.Event event, boolean ready) {
    Game g = session.game();
    if (availabilityGame != g || availabilityAt != g.elapsedMs) {
      availability.clear();
      availabilityGame = g;
      availabilityAt = g.elapsedMs;
    }
    String reason;
    if (session.sending()) reason = "Waiting for your previous action to be confirmed.";
    else if (session.paused()) reason = "Wait for every phone to reconnect.";
    else {
      String actionKey =
          event.type
              + "/"
              + event.actor
              + "/"
              + event.target
              + "/"
              + event.ability
              + "/"
              + event.value;
      reason = availability.computeIfAbsent(actionKey, k -> g.copy().apply(event));
    }
    if (!ready && reason.isEmpty()) reason = "Wait for the next room update.";
    Node block =
        col(
                "actionblock." + key,
                button(key, value, true, () -> action(event)).enabled(reason.isEmpty()))
            .gap(6);
    if (!reason.isEmpty()) block.add(text("actionreason." + key, reason, 12, MUTED));
    return block;
  }

  private void taskCard(List<Node> nodes, Game g, Game.Task task) {
    if (!inputTaskId.equals(task.id)) {
      inputTaskId = task.id;
      entered.clear();
    }
    Node card =
        card(
            "task",
            label(
                "task.clock",
                HuntIntel.title(task.kind) + " / " + seconds(task.endsAt) + "s",
                AMBER));
    if (!task.active()) {
      card.add(title("task.wait", "Waiting for a partner", 27));
      card.add(
          text(
              "task.wait.info",
              "Ask another player to scan "
                  + HuntIntel.title(task.station.name())
                  + " and tap Join. They must be nearby.",
              15,
              MUTED));
      card.add(
          button(
                  "task.rescan",
                  session.training ? "Refresh this checkpoint scan" : "Rescan this checkpoint",
                  false,
                  () -> {
                    if (session.training) {
                      String error = session.scanStation(task.station);
                      if (!error.isEmpty()) message(error);
                      render();
                    } else startScanner();
                  })
              .enabled(!session.sending()));
    } else if (task.reader.equals(session.self)) {
      card.add(label("task.role", "YOU ARE THE READER", TEAL));
      card.add(title("task.reader", "Tell " + g.name(task.operator) + " this sequence", 27));
      for (int n = 0; n < 3; n++)
        card.add(title("task.symbol." + n, (n + 1) + "   " + task.instructions().get(n), 27));
      card.add(
          text(
              "task.reader.info",
              "Read the symbols aloud in order. Your partner has the controls.",
              14,
              MUTED));
      if (session.training)
        card.add(
            button(
                "task.practice.tell",
                "Tell practice partner the sequence",
                true,
                () -> session.practiceSendInstructions(task.id)));
    } else {
      card.add(label("task.role", "YOU ARE THE OPERATOR", TEAL));
      card.add(title("task.operator", "Ask " + g.name(task.reader) + " for the sequence", 26));
      card.add(
          text(
              "task.operator.info",
              "Listen, tap three symbols in order, then submit. A wrong answer adds 5 Alarm.",
              14,
              MUTED));
      if (session.training)
        card.add(
            text(
                "task.practice.sequence",
                "Practice partner says: " + String.join(" → ", task.instructions()),
                15,
                AMBER));
      if (task.hints > 0)
        card.add(
            text(
                "task.hints",
                "Analyst hint: " + String.join(" → ", task.instructions().subList(0, task.hints)),
                15,
                TEAL));
      List<String> symbols = new ArrayList<>();
      for (int code : entered) symbols.add(Game.GLYPHS[code]);
      while (symbols.size() < 3) symbols.add("—");
      card.add(title("task.input", String.join("   /   ", symbols), 20));
      for (int row = 0; row < 2; row++) {
        Node buttons = row("task.glyphs." + row);
        for (int c = 0; c < 2; c++) {
          final int symbol = row * 2 + c;
          buttons.add(
              button(
                      "task.glyph." + symbol,
                      Game.GLYPHS[symbol],
                      false,
                      () -> {
                        if (entered.size() < 3) entered.add(symbol);
                        render();
                      })
                  .flex()
                  .enabled(entered.size() < 3 && !session.sending()));
        }
        card.add(buttons);
      }
      card.add(
          row(
              "task.submit.row",
              button(
                      "task.undo",
                      "Undo",
                      false,
                      () -> {
                        if (!entered.isEmpty()) entered.remove(entered.size() - 1);
                        render();
                      })
                  .flex()
                  .enabled(!entered.isEmpty()),
              button(
                      "task.submit",
                      "Submit sequence",
                      true,
                      () -> {
                        if (entered.size() == 3)
                          action(
                              Game.Event.answer(
                                  session.self,
                                  task.id,
                                  (entered.get(0) << 4) | (entered.get(1) << 2) | entered.get(2)));
                      })
                  .flex()
                  .enabled(entered.size() == 3 && !session.sending())));
    }
    if (task.active() && g.agents.get(session.self).specialty == Game.Specialty.ANALYST)
      card.add(
          actButton(
              "task.hint",
              "Reveal next symbol · Analyst",
              Game.Event.perk(session.self, ""),
              g.agents.get(session.self).perkReadyAt <= g.elapsedMs && task.hints < 3));
    card.add(
        button("task.cancel", "Cancel task", false, () -> action(Game.Event.cancel(session.self)))
            .enabled(!session.sending()));
    nodes.add(card);
  }

  private void crew(List<Node> nodes, Game g) {
    nodes.add(label("crew.label", "TRUST IS A RESOURCE", AMBER));
    nodes.add(title("crew.title", "Your crew.\nYour suspects.", 32));
    nodes.add(
        text(
            "crew.info",
            "Compare encounters, coordinate handoffs, and mark suspicions privately. Clues are"
                + " noisy; they are not proof.",
            15,
            MUTED));
    for (Game.Player p : g.players) {
      Game.Agent a = g.agents.get(p.id());
      HuntIntel.Case evidence = HuntIntel.inspect(g, session.self, p.id());
      String state =
          g.exposed && p.id().equals(g.ghost)
              ? "EXPOSED GHOST"
              : g.cleared.contains(p.id())
                  ? "CLEARED"
                  : watched.contains(p.id()) ? "WATCHING · PRIVATE NOTE" : "CREW";
      nodes.add(
          card(
              "crew." + p.id(),
              row(
                  "crew.head." + p.id(),
                  title(
                          "crew.name." + p.id(),
                          p.name() + (p.id().equals(session.self) ? " · you" : ""),
                          22)
                      .flex(),
                  label("crew.state." + p.id(), state, state.equals("EXPOSED GHOST") ? RED : TEAL)),
              text(
                  "crew.specialty." + p.id(),
                  HuntIntel.title(a.specialty.name())
                      + " · carrying "
                      + g.inventory(p.id()).size()
                      + " relic(s)",
                  14,
                  MUTED),
              text(
                  "crew.encounter." + p.id(),
                  p.id().equals(session.self)
                      ? "Your private notes stay on this phone."
                      : evidence.lastSeenAgoMs() < 0
                          ? "No recent encounter"
                          : evidence.lastSeenAgoMs() / 1000
                              + "s since encounter · "
                              + evidence.strongMatches()
                              + " strong-signal overlap(s)",
                  13,
                  MUTED),
              button("crew.dossier." + p.id(), "Open dossier", false, () -> dossier(p))));
    }
    nodes.add(
        card(
            "accusations",
            title("accusations.title", g.accusationsLeft + " accusation opportunities left", 23),
            text(
                "accusations.info",
                g.exposed
                    ? "The ghost is exposed. Focus on completing extraction."
                    : g.elapsedMs < 90000
                        ? "Accusations unlock in "
                            + seconds(90000)
                            + " seconds. Recover relics first."
                        : "A majority exposes the accused's allegiance. A wrong conviction adds 20"
                            + " Alarm. Exposing the ghost starts the showdown.",
                14,
                MUTED)));
    if (g.roomEvent.equals("BLACKOUT") && g.eventUntil > g.elapsedMs) {
      nodes.add(text("intel.blackout", "Signal history is hidden during the blackout.", 14, AMBER));
      return;
    }
    List<Game.Observation> history = g.history(session.self);
    nodes.add(label("intel.label", "YOUR LAST SIX SIGNAL READINGS", MUTED));
    nodes.add(text("intel.trend", HuntIntel.trend(history), 16, TEAL));
    for (Game.Observation o : history) {
      List<String> names = new ArrayList<>();
      for (String id : o.nearby()) names.add(g.name(id));
      nodes.add(
          text(
              "intel." + o.interval(),
              time(o.atMs())
                  + " · "
                  + new String[] {"Faint", "Nearby", "Strong"}[o.band()]
                  + " · "
                  + (names.isEmpty() ? "No encounters" : String.join(", ", names)),
              13,
              MUTED));
    }
  }

  private void dossier(Game.Player p) {
    Game g = session.game();
    boolean blackout = g.roomEvent.equals("BLACKOUT") && g.eventUntil > g.elapsedMs;
    HuntIntel.Case c = HuntIntel.inspect(g, session.self, p.id());
    String details =
        "Specialty: "
            + HuntIntel.title(g.agents.get(p.id()).specialty.name())
            + "\nCarrying: "
            + g.inventory(p.id()).size()
            + " relic(s)\n\nYour strong-signal overlaps: "
            + c.strongMatches()
            + "\nShared witnesses: "
            + c.witnesses()
            + "\nShared scan intervals: "
            + c.scans()
            + "\n\n"
            + "These are coincidences, not a guilt percentage. Talk about where each person was"
            + " when sabotage happened.";
    LinearLayout dossierBody = new LinearLayout(this);
    dossierBody.setOrientation(LinearLayout.VERTICAL);
    dossierBody.setPadding(d(20), d(8), d(20), d(12));
    TextView summary = new TextView(this);
    summary.setText(
        blackout
            ? "Specialty: "
                + HuntIntel.title(g.agents.get(p.id()).specialty.name())
                + "\nCarrying: "
                + g.inventory(p.id()).size()
                + " relic(s)\n\n"
                + "Signal analysis and the evidence graph are hidden during the blackout."
            : details);
    summary.setTextSize(14);
    summary.setTextColor(TEXT);
    dossierBody.addView(summary);
    SignalView graph = new SignalView(this);
    graph.setGraph(g, session.self);
    if (!blackout) dossierBody.addView(graph, new LinearLayout.LayoutParams(-1, d(260)));
    TextView legend = new TextView(this);
    legend.setText(
        "Lines show recent encounters during positive signal readings. Gold nodes carry relics."
            + " Connections do not establish guilt.");
    legend.setTextColor(MUTED);
    legend.setTextSize(12);
    if (!blackout) dossierBody.addView(legend);
    ScrollView dossierScroll = new ScrollView(this);
    dossierScroll.addView(dossierBody);
    AlertDialog.Builder b =
        new AlertDialog.Builder(this)
            .setTitle(p.name() + " · dossier")
            .setView(dossierScroll)
            .setNegativeButton("Close", null);
    if (!p.id().equals(session.self)) {
      b.setNeutralButton(
          watched.contains(p.id()) ? "Unmark" : "Watch privately",
          (v, w) -> {
            if (!watched.add(p.id())) watched.remove(p.id());
            render();
          });
      if (!g.exposed
          && !g.cleared.contains(p.id())
          && g.accusationsLeft > 0
          && g.elapsedMs >= 90000
          && g.vote == null) b.setPositiveButton("Accuse…", (v, w) -> confirmAccusation(p));
    }
    showDialog(
        b.create(),
        () ->
            session != null
                && session.screen == Session.Screen.ROUND
                && blackout
                    == (session.game().roomEvent.equals("BLACKOUT")
                        && session.game().eventUntil > session.game().elapsedMs));
  }

  private void confirmAccusation(Game.Player p) {
    showDialog(
        new AlertDialog.Builder(this)
            .setTitle("Accuse " + p.name() + "?")
            .setMessage(
                "This spends one crew accusation opportunity. The room gets 20 seconds to vote. A"
                    + " wrong conviction adds 20 Alarm; a correct one starts the showdown, and you"
                    + " still need to escape.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton(
                "Start crew vote", (v, w) -> action(Game.Event.tag(session.self, p.id())))
            .create(),
        () -> session != null && session.game().vote == null && !session.game().exposed);
  }

  private Node voteCard(Game g) {
    Game.Vote vote = g.vote;
    int required = (g.players.size() - 1) / 2 + 1;
    Node card =
        card(
            "vote",
            label("vote.clock", "CREW VOTE / " + seconds(vote.endsAt) + "s", AMBER),
            title("vote.title", "Is " + g.name(vote.suspect) + " the ghost?", 26),
            text(
                "vote.info",
                required
                    + " confirm votes are required. The accused cannot vote. Ballots are private.",
                14,
                MUTED));
    if (vote.suspect.equals(session.self))
      card.add(text("vote.suspect", "You are accused. Other players decide.", 15, AMBER));
    else if (vote.ballots.containsKey(session.self))
      card.add(text("vote.locked", "Your ballot is locked. Waiting for the crew.", 15, TEAL));
    else
      card.add(
          row(
              "vote.buttons",
              actButton("vote.yes", "Confirm", Game.Event.vote(session.self, true), true).flex(),
              actButton("vote.no", "Reject", Game.Event.vote(session.self, false), true).flex()));
    return card;
  }

  private void gear(List<Node> nodes, Game g) {
    if (g.exposed && session.isGhost()) {
      nodes.add(title("gear.showdown", "Your cover is blown.", 32));
      nodes.add(
          text(
              "gear.showdown.info",
              "Your heist equipment is disabled. Use your ghost tools to stop extraction while the"
                  + " crew races for the Exit.",
              16,
              MUTED));
      nodes.add(button("gear.ghost", "Open ghost controls", true, this::privateTools));
      return;
    }
    Game.Agent a = g.agents.get(session.self);
    nodes.add(
        label(
            "gear.label",
            "LOADOUT / " + HuntIntel.title(a.specialty.name()).toUpperCase(Locale.ROOT),
            AMBER));
    nodes.add(title("gear.title", "Carry the operation.", 32));
    nodes.add(
        text(
            "gear.capacity",
            g.inventory(session.self).size()
                + " / "
                + g.capacity(session.self)
                + " inventory slots occupied",
            15,
            MUTED));
    for (Game.Offer f : g.offers.values())
      if (f.to().equals(session.self))
        nodes.add(
            card(
                "offer." + f.relic(),
                title(
                    "offer.title." + f.relic(),
                    g.name(f.from()) + " offers " + g.relics.get(f.relic()).name,
                    23),
                text(
                    "offer.expiry." + f.relic(),
                    "Expires in " + seconds(f.expiresAt()) + " seconds",
                    13,
                    AMBER),
                row(
                    "offer.buttons." + f.relic(),
                    actButton(
                            "accept." + f.relic(),
                            "Accept",
                            Game.Event.accept(session.self, f.relic()),
                            true)
                        .flex(),
                    actButton(
                            "decline." + f.relic(),
                            "Decline",
                            Game.Event.decline(session.self, f.relic()),
                            true)
                        .flex())));
    for (Game.Relic r : g.inventory(session.self))
      nodes.add(
          card(
              "inventory." + r.id,
              art("inventory.art." + r.id, "relic/" + r.id, 120),
              title("inventory.name." + r.id, r.name, 25),
              label(
                  "inventory.state." + r.id,
                  r.state == Game.RelicState.SEALED
                      ? "SEALED · TAKE TO EXIT"
                      : "UNSEALED · TAKE TO WORKSHOP",
                  r.state == Game.RelicState.SEALED ? TEAL : AMBER),
              button(
                      "inventory.trade." + r.id,
                      "Offer a nearby handoff",
                      false,
                      () -> offerRelic(r))
                  .enabled(!session.sending()),
              text(
                  "inventory.offer." + r.id,
                  g.offers.containsKey(r.id)
                      ? "Waiting for " + g.name(g.offers.get(r.id).to()) + " to accept."
                      : "Handoffs require the other player's consent.",
                  12,
                  MUTED)));
    if (g.inventory(session.self).isEmpty())
      nodes.add(
          card(
              "inventory.empty",
              title("inventory.empty.title", "Hands free. Ready to help.", 24),
              text(
                  "inventory.empty.info",
                  "Recover a relic at the Vault or accept a nearby teammate's handoff.",
                  14,
                  MUTED)));
    String perk =
        switch (a.specialty) {
          case ANALYST ->
              "Reveal the next correct symbol during a paired puzzle. Recharges in 60 seconds.";
          case ENGINEER ->
              "Scan a jammed checkpoint and bypass its jam instantly. Recharges in 90 seconds.";
          case COURIER ->
              "Your second inventory slot is always active. Take an extra relic or help another"
                  + " carrier.";
          case WARDEN ->
              "Protect yourself or a nearby carrier from corruption and haunting for 30 seconds."
                  + " Recharges in 60 seconds.";
        };
    Node specialty =
        card(
            "specialty",
            title("specialty.title", HuntIntel.title(a.specialty.name()) + " equipment", 24),
            text("specialty.info", perk, 15, MUTED));
    if (a.specialty == Game.Specialty.WARDEN)
      specialty.add(
          button(
                  "specialty.use",
                  a.perkReadyAt > g.elapsedMs
                      ? "Recharging · " + seconds(a.perkReadyAt) + "s"
                      : "Use specialty",
                  true,
                  () -> {
                    if (a.specialty == Game.Specialty.WARDEN) chooseWarded();
                    else action(Game.Event.perk(session.self, ""));
                  })
              .enabled(a.perkReadyAt <= g.elapsedMs && !session.sending() && !session.paused()));
    else if (a.specialty != Game.Specialty.COURIER)
      specialty.add(
          actButton(
              "specialty.use",
              a.perkReadyAt > g.elapsedMs
                  ? "Recharging · " + seconds(a.perkReadyAt) + "s"
                  : "Use specialty",
              Game.Event.perk(session.self, ""),
              true));
    if (a.shieldUntil > g.elapsedMs)
      specialty.add(label("specialty.ward", "WARD ACTIVE / " + seconds(a.shieldUntil) + "s", TEAL));
    if (a.hauntedUntil > g.elapsedMs)
      specialty.add(label("specialty.haunt", "HAUNTED / " + seconds(a.hauntedUntil) + "s", RED));
    nodes.add(specialty);
    Node manifest = card("manifest", label("manifest.label", "RELIC MANIFEST", MUTED));
    for (Game.Relic r : g.relics.values())
      manifest.add(
          text(
              "manifest." + r.id,
              r.name
                  + " · "
                  + (r.state == Game.RelicState.DELIVERED
                      ? "secured at Exit"
                      : !r.owner.isEmpty()
                          ? "carried by " + g.name(r.owner)
                          : HuntIntel.title(r.location.name())),
              14,
              TEXT));
    nodes.add(manifest);
  }

  private void offerRelic(Game.Relic relic) {
    Game g = session.game();
    List<Game.Player> nearby = new ArrayList<>();
    for (Game.Player p : g.players)
      if (!p.id().equals(session.self) && g.recentlyNear(session.self, p.id())) nearby.add(p);
    if (nearby.isEmpty()) {
      message("Walk near a teammate and wait for the next Bluetooth reading.");
      return;
    }
    String[] names = nearby.stream().map(Game.Player::name).toArray(String[]::new);
    showDialog(
        new AlertDialog.Builder(this)
            .setTitle("Offer " + relic.name + " to…")
            .setItems(
                names,
                (v, w) -> action(Game.Event.offer(session.self, relic.id, nearby.get(w).id())))
            .setNegativeButton("Cancel", null)
            .create(),
        () -> session != null && session.game().relics.get(relic.id).owner.equals(session.self));
  }

  private void chooseWarded() {
    List<Game.Player> targets = new ArrayList<>();
    Game g = session.game();
    for (Game.Player p : g.players)
      if (p.id().equals(session.self) || g.recentlyNear(session.self, p.id())) targets.add(p);
    showDialog(
        new AlertDialog.Builder(this)
            .setTitle("Protect a carrier")
            .setItems(
                targets.stream().map(Game.Player::name).toArray(String[]::new),
                (v, w) -> action(Game.Event.perk(session.self, targets.get(w).id())))
            .setNegativeButton("Cancel", null)
            .create(),
        () -> session != null && session.screen == Session.Screen.ROUND);
  }

  private void action(Game.Event event) {
    if (session == null) return;
    String error = session.act(event);
    availability.clear();
    if (!error.isEmpty()) message(error);
    else {
      notice = session.sending() ? "Sending · waiting for room confirmation" : "Action complete.";
      sound();
    }
    render();
  }

  private void privateTools() {
    privateToolsOpen = true;
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(d(18), d(10), d(18), d(10));
    ScrollView scroller = new ScrollView(this);
    scroller.addView(content);
    dialogUi = new NativeUi(this, content);
    privateDialog =
        new AlertDialog.Builder(this)
            .setTitle(session.isGhost() ? "Ghost controls" : "Hunter briefing")
            .setView(scroller)
            .setNegativeButton("Hide", null)
            .create();
    privateDialog.setOnDismissListener(
        v -> {
          privateToolsOpen = false;
          dialogUi = null;
        });
    showSecure(privateDialog);
    updatePrivateTools();
  }

  private void updatePrivateTools() {
    if (dialogUi == null || session == null) return;
    Game g = session.game();
    List<Node> nodes = new ArrayList<>();
    if (!session.isGhost()) {
      nodes.add(title("brief.title", "Recover. Seal. Escape.", 25));
      nodes.add(
          text(
              "brief.info",
              "Use Mission to find your next action. Pair with a teammate at the Vault, take"
                  + " recovered relics to the Workshop, then deliver them to the Exit. Use Crew to"
                  + " discuss suspicions. Exposing the ghost does not complete the heist.",
              15,
              MUTED));
    } else {
      nodes.add(
          label(
              "ghost.energy",
              g.charges + " / 3 ENERGY · " + (g.exposed ? "REGEN EVERY 20s" : "REGEN EVERY 30s"),
              RED));
      nodes.add(
          text(
              "ghost.cooldown",
              g.abilityReadyAt > g.elapsedMs
                  ? "Tools recharge in " + seconds(g.abilityReadyAt) + " seconds."
                  : "Tools ready. Each use adds 5 Alarm.",
              14,
              MUTED));
      nodes.add(
          text(
              "ghost.goal",
              g.exposed
                  ? "You are exposed. Jam remotely and approach hunters to Haunt them. Win at 100"
                      + " Alarm or when time expires."
                  : "Keep helping as cover. Jam a checkpoint you recently scanned, or approach a"
                      + " carrier to corrupt a sealed relic.",
              14,
              MUTED));
      for (Game.Station s : List.of(Game.Station.VAULT, Game.Station.WORKSHOP, Game.Station.EXIT))
        nodes.add(
            actButton(
                "ghost.jam." + s,
                "Jam " + HuntIntel.title(s.name()) + " · 1 energy",
                Game.Event.ability(session.self, "JAM", s.name()),
                g.charges >= 1
                    && g.abilityReadyAt <= g.elapsedMs
                    && (g.exposed || g.visited(session.self, s))
                    && g.jamReadyAt.getOrDefault(s, 0) <= g.elapsedMs));
      if (!g.exposed) {
        for (Game.Relic r : g.relics.values())
          if (r.state == Game.RelicState.SEALED
              && !r.owner.isEmpty()
              && !r.owner.equals(session.self))
            nodes.add(
                actButton(
                    "ghost.corrupt." + r.id,
                    "Corrupt " + r.name + " · 2 energy",
                    Game.Event.ability(session.self, "CORRUPT", r.id),
                    g.charges >= 2
                        && g.abilityReadyAt <= g.elapsedMs
                        && g.recentlyNear(session.self, r.owner)
                        && r.protectedUntil <= g.elapsedMs
                        && g.agents.get(r.owner).shieldUntil <= g.elapsedMs));
      } else
        for (Game.Player p : g.players)
          if (!p.id().equals(session.self))
            nodes.add(
                actButton(
                    "ghost.haunt." + p.id(),
                    "Haunt " + p.name() + " · 1 energy",
                    Game.Event.ability(session.self, "HAUNT", p.id()),
                    g.charges >= 1
                        && g.abilityReadyAt <= g.elapsedMs
                        && g.recentlyNear(session.self, p.id())
                        && g.agents.get(p.id()).immuneUntil <= g.elapsedMs
                        && g.agents.get(p.id()).shieldUntil <= g.elapsedMs));
    }
    dialogUi.render(nodes);
  }

  private void showSecure(AlertDialog value) {
    value.setOnShowListener(
        v -> value.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
    value.show();
  }

  private void showDialog(AlertDialog value, BooleanSupplier valid) {
    if (dialog != null) dialog.dismiss();
    dialog = value;
    dialogValid = valid;
    dialog.show();
  }

  private void dismissDialogs() {
    if (dialog != null) dialog.dismiss();
    if (privateDialog != null) privateDialog.dismiss();
    privateToolsOpen = false;
    dialog = null;
    privateDialog = null;
    dialogUi = null;
    dialogValid = () -> true;
  }

  private void results() {
    Game g = session.game();
    page("results", false);
    List<Node> nodes = new ArrayList<>();
    nodes.add(header("Debrief", () -> {}));
    nodes.add(
        label(
            "result.winner",
            g.winner.equals("HUNTERS")
                ? "CREW WINS"
                : g.winner.equals("GHOST") ? "GHOST WINS" : "OPERATION INTERRUPTED",
            g.winner.equals("HUNTERS") ? TEAL : RED));
    nodes.add(
        title(
            "result.title",
            g.winner.equals("HUNTERS")
                ? "The relics are yours.\nYou made it out."
                : g.winner.equals("GHOST")
                    ? "The facility\nclaimed the crew."
                    : "The signal\nwent dark.",
            37));
    nodes.add(art("result.art", g.winner.equals("GHOST") ? "ghost" : "heist", 180));
    nodes.add(text("result.reason", g.endReason, 16, MUTED));
    nodes.add(
        card(
            "result.summary",
            label("result.ghost.label", "THE GHOST WAS", MUTED),
            title("result.ghost", g.name(g.ghost), 29),
            text(
                "result.stats",
                g.delivered()
                    + "/3 relics · Alarm "
                    + g.alarm
                    + " · "
                    + time(g.elapsedMs)
                    + " elapsed",
                14,
                MUTED)));
    nodes.add(button("result.replay", "Back to the entrance", true, this::closeSession));
    nodes.add(label("timeline.label", "OPERATION TIMELINE / FULL DECLASSIFICATION", MUTED));
    int i = 0;
    for (Game.Entry e : g.visibleEvents(session.self, true))
      nodes.add(text("timeline." + i++, time(e.atMs()) + " · " + e.detail(), 14, MUTED));
    ui.render(nodes);
  }

  private void guide() {
    page("guide/" + guideStep, false);
    List<Node> nodes = new ArrayList<>();
    nodes.add(
        header(
            "Close guide",
            () -> {
              guideStep = -1;
              render();
            }));
    nodes.add(label("guide.step", "FIELD GUIDE / " + (guideStep + 1) + " OF 5", AMBER));
    if (guideStep == 0) {
      nodes.add(title("guide.title", "One crew.\nOne hidden ghost.", 36));
      nodes.add(art("guide.art", "heist", 170));
      nodes.add(
          text(
              "guide.info",
              "Hunters must recover three relics, seal them at the Workshop, and open the Exit. The"
                  + " ghost secretly sabotages this route. Time or 100 Alarm means a ghost"
                  + " victory.",
              17,
              MUTED));
      nodes.add(button("guide.next", "Learn the first team task →", true, () -> guideNext()));
    }
    if (guideStep == 1) {
      nodes.add(title("guide.title", "Your partner has\nthe missing information.", 31));
      nodes.add(
          text(
              "guide.info",
              "At the Vault, one player reads a sequence and the other taps the symbols. Try it:"
                  + " your partner says MOON → KEY → EYE.",
              16,
              MUTED));
      nodes.add(label("guide.progress", guideProgress + " / 3 SYMBOLS ENTERED", TEAL));
      for (int row = 0; row < 2; row++) {
        Node line = row("guide.row." + row);
        for (int n = 0; n < 2; n++) {
          final int symbol = row * 2 + n;
          line.add(
              button(
                      "guide.symbol." + symbol,
                      Game.GLYPHS[symbol],
                      false,
                      () -> {
                        int[] answer = {0, 1, 2};
                        if (guideProgress < 3 && symbol == answer[guideProgress]) {
                          guideProgress++;
                          guideFeedback =
                              guideProgress == 3
                                  ? "Breach complete. You recovered a relic!"
                                  : "Correct. Ask for the next symbol.";
                        } else {
                          guideProgress = 0;
                          guideFeedback =
                              "Try again: MOON, KEY, EYE. In a match a failed submission adds"
                                  + " Alarm.";
                        }
                        render();
                      })
                  .flex());
        }
        nodes.add(line);
      }
      nodes.add(
          button("guide.next", "Carry your recovered relic →", true, () -> guideNext())
              .enabled(guideProgress == 3));
    }
    if (guideStep == 2) {
      nodes.add(title("guide.title", "Loot needs a route.", 34));
      nodes.add(
          text(
              "guide.info",
              "You are carrying an unsealed relic. Which checkpoint comes next?",
              17,
              MUTED));
      nodes.add(
          button(
              "guide.workshop",
              "Workshop · seal the relic",
              true,
              () -> {
                guideFeedback =
                    "Correct. Seal it, deliver it to the Exit, then recover another relic.";
                guideProgress = 1;
                render();
              }));
      nodes.add(
          button(
              "guide.exit",
              "Exit · deliver immediately",
              false,
              () -> {
                guideFeedback = "The Exit only accepts sealed relics. Visit the Workshop first.";
                render();
              }));
      nodes.add(
          text(
              "guide.trade",
              "A teammate can accept a nearby handoff. Couriers carry two relics; everyone else"
                  + " carries one.",
              15,
              MUTED));
      nodes.add(
          button("guide.next", "Learn the danger →", true, () -> guideNext())
              .enabled(guideProgress == 1));
    }
    if (guideStep == 3) {
      nodes.add(title("guide.title", "Sabotage changes\nyour plans.", 34));
      nodes.add(
          text(
              "guide.info",
              "Jammed checkpoint? Solve a repair puzzle with a partner, or ask an Engineer."
                  + " Corrupted relic? Take it back to the Workshop. A Warden can protect a"
                  + " carrier. Use the Workshop purge to lower Alarm.",
              17,
              MUTED));
      nodes.add(
          text(
              "guide.alarm",
              "Incorrect puzzle: +5 Alarm\nWrong conviction: +20 Alarm\n100 Alarm: ghost victory",
              16,
              AMBER));
      nodes.add(button("guide.next", "What if we expose the ghost? →", true, () -> guideNext()));
    }
    if (guideStep == 4) {
      nodes.add(title("guide.title", "The reveal starts\nthe showdown.", 34));
      nodes.add(
          text(
              "guide.info",
              "After 90 seconds, the crew can spend one of two accusations on a private vote."
                  + " Exposing the ghost unlocks their open attacks—it does not end the match."
                  + " Hunters must still deliver all three relics and solve the final Exit puzzle.",
              17,
              MUTED));
      if (session == null)
        nodes.add(button("guide.practice", "Start a practice heist", true, this::trainingDialog));
      nodes.add(
          button(
              "guide.return",
              session == null ? "Ready · back to entrance" : "Return to my heist",
              false,
              () -> {
                guideStep = -1;
                render();
              }));
    }
    if (!guideFeedback.isEmpty())
      nodes.add(card("guide.feedback", text("guide.feedback.text", guideFeedback, 15, TEAL)));
    ui.render(nodes);
  }

  private void guideNext() {
    guideStep++;
    guideProgress = 0;
    guideFeedback = "";
    render();
  }

  private void settings() {
    captureCallsign();
    LinearLayout content = new LinearLayout(this);
    content.setPadding(d(20), d(10), d(20), d(10));
    content.setOrientation(LinearLayout.VERTICAL);
    CheckBox motion = new CheckBox(this);
    motion.setText("Reduce motion");
    motion.setChecked(getSharedPreferences("accessibility", 0).getBoolean("reduceMotion", false));
    content.addView(motion);
    CheckBox sound = new CheckBox(this);
    sound.setText("Subtle action sounds");
    sound.setChecked(getPreferences(0).getBoolean("sound", false));
    content.addView(sound);
    new AlertDialog.Builder(this)
        .setTitle("Comfort settings")
        .setView(content)
        .setPositiveButton(
            "Save",
            (v, w) -> {
              getSharedPreferences("accessibility", 0)
                  .edit()
                  .putBoolean("reduceMotion", motion.isChecked())
                  .apply();
              getPreferences(0).edit().putBoolean("sound", sound.isChecked()).apply();
              pageKey = "";
              render();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void sound() {
    if (!getPreferences(0).getBoolean("sound", false)) return;
    try {
      if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 25);
      tone.startTone(ToneGenerator.TONE_PROP_ACK, 70);
    } catch (RuntimeException ignored) {
    }
  }

  private void openSession(String code, boolean host, boolean training, boolean ghost) {
    closeScanner();
    roundPages.clear();
    availability.clear();
    availabilityGame = null;
    if (session != null) session.close();
    dismissDialogs();
    hideKeyboard();
    roleRead = false;
    tab = "Mission";
    pageKey = "";
    notice = "";
    setupOpen = false;
    watched.clear();
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
              public void changed() {
                if (training) availability.clear();
                scheduleRender();
              }

              public void message(String value) {
                MainActivity.this.message(value);
              }
            });
    session.verifiedCheckpoints.addAll(setupVerified);
    render();
  }

  private void message(String value) {
    notice = value;
    toast(value);
    scheduleRender();
  }

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
  }

  private void leave() {
    new AlertDialog.Builder(this)
        .setTitle(session != null && session.training ? "Leave practice?" : "Leave the operation?")
        .setMessage(
            session != null && session.training
                ? "Your practice heist will end."
                : "The crew will pause while your phone is absent. A prolonged absence ends the"
                    + " heist without a winner.")
        .setNegativeButton("Stay", null)
        .setPositiveButton("Leave", (v, w) -> closeSession())
        .show();
  }

  private void closeSession() {
    closeScanner();
    dismissDialogs();
    if (session != null) {
      session.close();
      session = null;
    }
    handler.removeCallbacks(renderTask);
    renderScheduled = false;
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    pageKey = "";
    notice = "";
    setupOpen = false;
    guideStep = -1;
    roundPages.clear();
    availability.clear();
    availabilityGame = null;
    entered.clear();
    inputTaskId = "";
    watched.clear();
    render();
  }

  private void back() {
    if (scanner != null) {
      closeScanner();
      return;
    }
    if (guideStep >= 0) {
      guideStep = -1;
      render();
      return;
    }
    if (setupOpen) {
      setupOpen = false;
      render();
      return;
    }
    if (session != null) leave();
    else finish();
  }

  @SuppressLint("GestureBackNavigation")
  @Override
  public void onBackPressed() {
    back();
  }

  private void startScanner() {
    if (scanner != null) return;
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 20);
      return;
    }
    scannerLayer = new FrameLayout(this);
    scannerLayer.setBackgroundColor(BG);
    root.addView(scannerLayer, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    scannerLayer.addView(layout, new FrameLayout.LayoutParams(-1, -1));
    TextView title = new TextView(this);
    title.setText("SCAN A CHECKPOINT\nVault · Workshop · Exit");
    title.setTextColor(TEXT);
    title.setTextSize(19);
    title.setPadding(d(22), d(24), d(22), d(18));
    layout.addView(title);
    scanner = new DecoratedBarcodeView(this);
    scanner
        .getBarcodeView()
        .setDecoderFactory(new DefaultDecoderFactory(List.of(BarcodeFormat.QR_CODE)));
    layout.addView(scanner, new LinearLayout.LayoutParams(-1, 0, 1));
    Button close = new Button(this);
    close.setText("Cancel scanning");
    close.setOnClickListener(v -> closeScanner());
    layout.addView(close, new LinearLayout.LayoutParams(-1, d(60)));
    DecoratedBarcodeView activeScanner = scanner;
    scanner.decodeContinuous(
        new BarcodeCallback() {
          public void barcodeResult(BarcodeResult result) {
            if (scanner != activeScanner) return;
            try {
              Game.Station station = CheckpointCode.decode(result.getText());
              closeScanner();
              if (setupOpen || session == null || session.screen == Session.Screen.LOBBY) {
                setupVerified.add(station);
                if (session != null) session.verifiedCheckpoints.add(station);
                notice = HuntIntel.title(station.name()) + " checkpoint verified.";
              } else {
                String error = session.scanStation(station);
                notice =
                    error.isEmpty()
                        ? "Checkpoint scanned: " + HuntIntel.title(station.name())
                        : error;
              }
              sound();
              render();
            } catch (IllegalArgumentException e) {
              if (scanner == activeScanner) scanner.setStatusText(e.getMessage());
            }
          }
        });
    scanner.resume();
  }

  private void closeScanner() {
    if (scanner != null) {
      scanner.pause();
      scanner = null;
    }
    if (scannerLayer != null) {
      root.removeView(scannerLayer);
      scannerLayer = null;
    }
  }

  private void prepareRadio(Runnable next) {
    pendingRadio = next;
    List<String> permissions = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 31)
      permissions.addAll(
          List.of(
              Manifest.permission.BLUETOOTH_SCAN,
              Manifest.permission.BLUETOOTH_ADVERTISE,
              Manifest.permission.BLUETOOTH_CONNECT));
    permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
    permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
    permissions.removeIf(p -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
    if (!permissions.isEmpty()) {
      new AlertDialog.Builder(this)
          .setTitle("Connect the crew")
          .setMessage(
              "Allow Nearby devices and precise location so Android can discover nearby phones. The"
                  + " game uses Bluetooth proximity, not GPS. No information leaves the room.")
          .setNegativeButton("Cancel", (v, w) -> pendingRadio = null)
          .setPositiveButton(
              "Continue", (v, w) -> requestPermissions(permissions.toArray(new String[0]), 10))
          .show();
      return;
    }
    finishRadioSetup();
  }

  @SuppressLint("MissingPermission")
  private void finishRadioSetup() {
    BluetoothManager manager = getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null) {
      pendingRadio = null;
      toast("Bluetooth LE is unavailable. Practice remains available.");
      return;
    }
    if (!adapter.isEnabled()) {
      startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 11);
      return;
    }
    if (!getSystemService(LocationManager.class).isLocationEnabled()) {
      new AlertDialog.Builder(this)
          .setTitle("Enable local proximity")
          .setMessage(
              "Turn on Android Location so nearby Bluetooth scanning works. The game does not use"
                  + " GPS.")
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
    boolean granted = results.length > 0;
    for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
    if (request == 20) {
      if (granted) startScanner();
      else
        toast(
            "Camera permission is needed to scan checkpoints. You can enable it in Android App"
                + " settings.");
    } else if (request == 10) {
      if (granted) finishRadioSetup();
      else {
        pendingRadio = null;
        new AlertDialog.Builder(this)
            .setTitle("Nearby permissions required")
            .setMessage(
                "Allow Nearby devices and precise location in Android App settings. Practice needs"
                    + " neither.")
            .setPositiveButton(
                "App settings",
                (v, w) ->
                    startActivity(
                        new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()))))
            .setNegativeButton("Close", null)
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

  @Override
  protected void onStart() {
    super.onStart();
    if (session != null) session.resume();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (scanner != null) scanner.resume();
    if (returningFromLocation) {
      returningFromLocation = false;
      if (getSystemService(LocationManager.class).isLocationEnabled()) finishRadioSetup();
      else pendingRadio = null;
    }
    scheduleRender();
  }

  @Override
  protected void onPause() {
    if (scanner != null) scanner.pause();
    super.onPause();
  }

  @Override
  protected void onStop() {
    super.onStop();
    touching = false;
    touchRenderPending = false;
    dismissDialogs();
    if (session != null) session.suspend();
  }

  @Override
  protected void onDestroy() {
    closeScanner();
    dismissDialogs();
    if (session != null) session.close();
    handler.removeCallbacksAndMessages(null);
    if (tone != null) tone.release();
    super.onDestroy();
  }
}
