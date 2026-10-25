package com.ghostprotocol.app;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import com.ghostprotocol.core.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Native touch-driven heist acceptance scenarios against the production activity and simulator. */
final class HeistUiScenario {
  private final Instrumentation runner;
  private MainActivity activity;
  private int passed;

  HeistUiScenario(Instrumentation runner) {
    this.runner = runner;
  }

  private interface Scenario {
    void run() throws Exception;
  }

  private void main(Runnable task) {
    Throwable[] error = {null};
    runner.runOnMainSync(
        () -> {
          try {
            task.run();
          } catch (Throwable e) {
            error[0] = e;
          }
        });
    if (error[0] != null) throw new AssertionError(error[0]);
  }

  private Object get(String name) {
    try {
      Field f = MainActivity.class.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(activity);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private void call(String name) {
    try {
      Method m = MainActivity.class.getDeclaredMethod(name);
      m.setAccessible(true);
      m.invoke(activity);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private Session session() {
    return (Session) get("session");
  }

  private Game game() {
    return session().game();
  }

  private NativeUi ui(boolean privateUi) {
    return (NativeUi) get(privateUi ? "dialogUi" : "ui");
  }

  private View view(String key, boolean privateUi) {
    NativeUi ui = ui(privateUi);
    View view = ui == null ? null : ui.view(key);
    if (view == null && !privateUi && get("navUi") instanceof NativeUi navigation)
      view = navigation.view(key);
    return view;
  }

  private void assertTrue(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private void waitFor(BooleanSupplier condition, int ms, String message) {
    long deadline = SystemClock.elapsedRealtime() + ms;
    while (SystemClock.elapsedRealtime() < deadline) {
      boolean[] yes = {false};
      main(() -> yes[0] = condition.getAsBoolean());
      if (yes[0]) return;
      SystemClock.sleep(100);
    }
    throw new AssertionError(message);
  }

  private void click(String key) throws Exception {
    click(key, false);
  }

  private void click(String key, boolean privateUi) throws Exception {
    waitFor(
        () -> view(key, privateUi) != null && view(key, privateUi).isEnabled(),
        12000,
        "Button unavailable: " + key);
    main(
        () -> {
          View v = view(key, privateUi);
          v.requestRectangleOnScreen(new Rect(0, 0, v.getWidth(), v.getHeight()), true);
        });
    SystemClock.sleep(120);
    View[] target = {null};
    main(() -> target[0] = view(key, privateUi));
    tap(target[0]);
    SystemClock.sleep(200);
  }

  private void tap(View view) throws Exception {
    int[] point = new int[2];
    main(
        () -> {
          assertTrue(view != null && view.isEnabled() && view.isShown(), "Tap target not visible");
          view.getLocationOnScreen(point);
          point[0] += view.getWidth() / 2;
          point[1] += view.getHeight() / 2;
        });
    long now = SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0),
        up = MotionEvent.obtain(now, now + 70, MotionEvent.ACTION_UP, point[0], point[1], 0);
    runner.sendPointerSync(down);
    runner.sendPointerSync(up);
    down.recycle();
    up.recycle();
  }

  private void dialogButton(String field, int which) throws Exception {
    AlertDialog[] value = {null};
    waitFor(
        () -> get(field) instanceof AlertDialog && ((AlertDialog) get(field)).isShowing(),
        3000,
        "Missing dialog " + field);
    main(() -> value[0] = (AlertDialog) get(field));
    tap(value[0].getButton(which));
    SystemClock.sleep(200);
  }

  private void screenshot(String name) throws IOException {
    Bitmap bitmap = runner.getUiAutomation().takeScreenshot();
    if (bitmap == null) return;
    File dir = new File(runner.getTargetContext().getExternalFilesDir(null), "heist-smoke");
    dir.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
  }

  private void test(String name, Scenario scenario) throws Exception {
    Bundle b = new Bundle();
    b.putString("stream", "RUN " + name + "\n");
    runner.sendStatus(1, b);
    scenario.run();
    passed++;
    b = new Bundle();
    b.putString("stream", "PASS " + name + "\n");
    runner.sendStatus(0, b);
  }

  private void startPractice(boolean ghost) throws Exception {
    // Open the same public practice entrypoint, then touch the native role-selection button.
    main(() -> call("trainingDialog"));
    SystemClock.sleep(150);
    AccessibilityNodeInfo root = runner.getUiAutomation().getRootInActiveWindow();
    AccessibilityNodeInfo choice = find(root, ghost ? "Play ghost" : "Play hunter");
    assertTrue(choice != null, "Practice choice missing");
    choice.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    SystemClock.sleep(250);
    click("role.reveal");
    main(
        () ->
            assertTrue(
                (((AlertDialog) get("privateDialog")).getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_SECURE)
                    != 0,
                "Private role window must be secure"));
    dialogButton("privateDialog", AlertDialog.BUTTON_POSITIVE);
    click("role.ready");
    waitFor(() -> session().begun(), 3000, "Practice did not start");
  }

  private boolean hasGraph(View view) {
    if (view instanceof SignalView) return true;
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++)
        if (hasGraph(group.getChildAt(n))) return true;
    return false;
  }

  private String shellOutput(String command) throws IOException {
    ParcelFileDescriptor descriptor = runner.getUiAutomation().executeShellCommand(command);
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(descriptor)))) {
      StringBuilder text = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) text.append(line).append('\n');
      return text.toString();
    }
  }

  private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
    if (node == null) return null;
    if (node.getText() != null
        && node.getText().toString().equalsIgnoreCase(text)
        && node.isVisibleToUser()) return node;
    for (int n = 0; n < node.getChildCount(); n++) {
      AccessibilityNodeInfo found = find(node.getChild(n), text);
      if (found != null) return found;
    }
    return null;
  }

  private void solve() throws Exception {
    waitFor(
        () -> game().taskFor(session().self) != null && game().taskFor(session().self).active(),
        12000,
        "Practice partner did not join");
    int[] sequence = new int[3];
    main(
        () -> {
          int code = game().taskFor(session().self).answerCode();
          sequence[0] = (code >> 4) & 3;
          sequence[1] = (code >> 2) & 3;
          sequence[2] = code & 3;
        });
    for (int symbol : sequence) click("task.glyph." + symbol);
    click("task.submit");
  }

  void run() {
    try {
      runner.getUiAutomation();
      activity =
          (MainActivity)
              runner.startActivitySync(
                  new Intent(runner.getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      SystemClock.sleep(500);
      test(
          "Home and interactive field guide",
          () -> {
            waitFor(() -> view("hero", false) != null, 3000, "Home missing");
            screenshot("heist-home");
            click("learn");
            click("guide.next");
            click("guide.symbol.0");
            click("guide.symbol.1");
            click("guide.symbol.2");
            click("guide.next");
            click("guide.exit");
            main(
                () ->
                    assertTrue(
                        ((TextView) view("guide.feedback.text", false))
                            .getText()
                            .toString()
                            .contains("Workshop"),
                        "Wrong route should explain correction"));
            click("guide.workshop");
            click("guide.next");
            click("guide.next");
            screenshot("heist-guide");
            click("guide.return");
          });
      test(
          "Offline printable checkpoint kit and QR decoding",
          () -> {
            File dir = new File(runner.getTargetContext().getExternalFilesDir(null), "heist-smoke");
            dir.mkdirs();
            for (Game.Station station : Game.Station.values())
              if (station != Game.Station.NONE) {
                Bitmap bitmap = CheckpointKit.qr(station, 600);
                int[] pixels = new int[600 * 600];
                bitmap.getPixels(pixels, 0, 600, 0, 0, 600, 600);
                Result decoded =
                    new MultiFormatReader()
                        .decode(
                            new BinaryBitmap(
                                new HybridBinarizer(new RGBLuminanceSource(600, 600, pixels))));
                assertTrue(CheckpointCode.decode(decoded.getText()) == station, "QR round trip");
                try (FileOutputStream out =
                    new FileOutputStream(
                        new File(
                            dir,
                            "checkpoint-" + station.name().toLowerCase(Locale.ROOT) + ".png"))) {
                  bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                bitmap.recycle();
              }
            try (FileOutputStream out =
                new FileOutputStream(new File(dir, "GhostProtocol-Checkpoints.pdf"))) {
              CheckpointKit.writePdf(out);
            }
            assertTrue(new File(dir, "GhostProtocol-Checkpoints.pdf").length() > 1000, "PDF empty");
          });
      test(
          "Private role reveal and practice start",
          () -> {
            startPractice(false);
            screenshot("heist-mission");
          });
      test(
          "Stable controls and clock under callback bursts",
          () -> {
            View[] before = {null};
            String[] time = {""};
            main(
                () -> {
                  before[0] = view("practice.VAULT", false);
                  time[0] = ((TextView) get("liveTimer")).getText().toString();
                });
            AtomicBoolean running = new AtomicBoolean(true);
            Handler handler = new Handler(Looper.getMainLooper());
            Runnable burst =
                new Runnable() {
                  public void run() {
                    if (!running.get()) return;
                    call("scheduleRender");
                    handler.postDelayed(this, 10);
                  }
                };
            handler.post(burst);
            SystemClock.sleep(2300);
            main(
                () -> {
                  assertTrue(
                      before[0] == view("practice.VAULT", false),
                      "Live updates replaced a control");
                  assertTrue(
                      !time[0].equals(((TextView) get("liveTimer")).getText().toString()),
                      "Clock stalled");
                });
            running.set(false);
            handler.removeCallbacks(burst);
          });
      test(
          "Waiting scan renewal, dossier graph and safe live help",
          () -> {
            Session active = session();
            java.lang.reflect.Field handlerField = Session.class.getDeclaredField("handler"),
                pulseField = Session.class.getDeclaredField("pulse"),
                tickField = Session.class.getDeclaredField("lastTick");
            handlerField.setAccessible(true);
            pulseField.setAccessible(true);
            tickField.setAccessible(true);
            Handler sessionHandler = (Handler) handlerField.get(active);
            Runnable pulse = (Runnable) pulseField.get(active);
            main(
                () -> {
                  sessionHandler.removeCallbacks(pulse);
                  Game g = game();
                  g.apply(Game.Event.scan(active.self, Game.Station.VAULT));
                  g.apply(Game.Event.task(active.self, "BREACH", ""));
                  for (int n = 0; n < 4; n++) g.apply(Game.Event.tick(4000, g.ids()));
                  call("render");
                });
            String[] taskId = {""};
            main(
                () -> {
                  taskId[0] = game().taskFor(active.self).id;
                  assertTrue(
                      !game().visited(active.self, Game.Station.VAULT),
                      "Fixture scan should have expired");
                });
            try {
              click("task.rescan");
              main(
                  () ->
                      assertTrue(
                          game().taskFor(active.self).id.equals(taskId[0])
                              && game().visited(active.self, Game.Station.VAULT),
                          "Rescan canceled the task"));
              click("task.cancel");
            } finally {
              tickField.setLong(active, SystemClock.elapsedRealtime());
              sessionHandler.post(pulse);
            }
            click("nav.Crew");
            String[] person = {""};
            main(() -> person[0] = game().players.get(1).id());
            click("crew.dossier." + person[0]);
            main(
                () ->
                    assertTrue(
                        hasGraph(((AlertDialog) get("dialog")).getWindow().getDecorView()),
                        "Dossier graph missing"));
            dialogButton("dialog", AlertDialog.BUTTON_NEGATIVE);
            main(
                () -> {
                  game().roomEvent = "BLACKOUT";
                  game().eventUntil = game().elapsedMs + 20000;
                  call("render");
                });
            click("crew.dossier." + person[0]);
            main(
                () ->
                    assertTrue(
                        !hasGraph(((AlertDialog) get("dialog")).getWindow().getDecorView()),
                        "Blackout did not hide the evidence graph"));
            dialogButton("dialog", AlertDialog.BUTTON_NEGATIVE);
            main(
                () -> {
                  game().roomEvent = "";
                  game().eventUntil = 0;
                  call("render");
                });
            click("fieldguide");
            main(
                () -> {
                  try {
                    Field step = MainActivity.class.getDeclaredField("guideStep");
                    step.setAccessible(true);
                    step.setInt(activity, 4);
                    call("render");
                  } catch (Exception e) {
                    throw new AssertionError(e);
                  }
                });
            main(
                () ->
                    assertTrue(
                        view("guide.practice", false) == null && session() == active,
                        "Help can replace a live session"));
            click("guide.return");
            click("nav.Mission");
            click("practice.WORKSHOP");
            main(
                () -> {
                  game().alarm = 5;
                  game().purgeReadyAt = game().elapsedMs + 60000;
                  ((Map<?, ?>) get("availability")).clear();
                  call("render");
                });
            main(
                () ->
                    assertTrue(
                        ((TextView) view("actionreason.purge", false))
                            .getText()
                            .toString()
                            .contains("recharges"),
                        "Disabled purge lacks an inline reason"));
            main(
                () -> {
                  game().alarm = 0;
                  game().purgeReadyAt = 0;
                  ((Map<?, ?>) get("availability")).clear();
                  call("render");
                });
          });
      test(
          "Touch-driven recovery, sealing, delivery and extraction",
          () -> {
            for (int n = 0; n < 3; n++) {
              click("practice.VAULT");
              click("breach");
              solve();
              waitFor(() -> !game().inventory(session().self).isEmpty(), 3000, "No relic awarded");
              String[] relic = {""};
              main(() -> relic[0] = game().inventory(session().self).get(0).id);
              if (n == 0) {
                click("nav.Gear");
                screenshot("heist-gear");
                click("nav.Crew");
                screenshot("heist-crew");
                click("nav.Mission");
                main(
                    () ->
                        assertTrue(
                            ((ScrollView) get("scroll")).getScrollY() == 0,
                            "Tab switch inherited scroll"));
              }
              click("practice.WORKSHOP");
              click("seal." + relic[0]);
              waitFor(
                  () -> game().relics.get(relic[0]).state == Game.RelicState.SEALED,
                  9000,
                  "Sealing did not complete");
              click("practice.EXIT");
              click("deposit." + relic[0]);
            }
            click("extract");
            solve();
            waitFor(() -> game().winner.equals("HUNTERS"), 3000, "Final extraction failed");
            main(
                () ->
                    assertTrue(
                        !game().exposed, "Extraction should work without identifying the ghost"));
            screenshot("heist-debrief");
            click("result.replay");
          });
      test(
          "Ghost sabotage, cooldown and active showdown",
          () -> {
            startPractice(true);
            click("practice.VAULT");
            click("briefing");
            click("ghost.jam.VAULT", true);
            main(
                () -> {
                  assertTrue(game().charges == 1, "Jam spent the wrong energy");
                  assertTrue(
                      !view("ghost.jam.VAULT", true).isEnabled(), "Cooldown button still enabled");
                });
            dialogButton("privateDialog", AlertDialog.BUTTON_NEGATIVE);
            main(
                () -> {
                  Game g = game();
                  while (g.elapsedMs < 90000)
                    g.apply(Game.Event.tick(Math.min(5000, 90000 - g.elapsedMs), g.ids()));
                  List<String> voters = new ArrayList<>(g.ids());
                  voters.remove(session().self);
                  g.apply(Game.Event.tag(voters.get(0), session().self));
                  for (String id : voters)
                    if (g.vote != null && !g.vote.ballots.containsKey(id))
                      g.apply(Game.Event.vote(id, true));
                  g.apply(Game.Event.observation(session().self, 1, voters));
                  call("render");
                });
            waitFor(() -> game().exposed, 3000, "Ghost did not enter showdown");
            click("briefing");
            String[] target = {""};
            main(
                () -> {
                  for (String id : game().ids())
                    if (!id.equals(session().self)) {
                      target[0] = id;
                      break;
                    }
                });
            click("ghost.haunt." + target[0], true);
            main(
                () ->
                    assertTrue(
                        game().agents.get(target[0]).hauntedUntil > game().elapsedMs,
                        "Haunt not applied"));
            dialogButton("privateDialog", AlertDialog.BUTTON_NEGATIVE);
            screenshot("heist-showdown");
            main(() -> call("closeSession"));
          });
      test(
          "Large text keeps mission navigation and controls usable",
          () -> {
            android.content.res.Configuration original =
                new android.content.res.Configuration(activity.getResources().getConfiguration());
            try {
              main(
                  () -> {
                    android.content.res.Configuration large =
                        new android.content.res.Configuration(original);
                    large.fontScale = 1.6f;
                    activity
                        .getResources()
                        .updateConfiguration(large, activity.getResources().getDisplayMetrics());
                    try {
                      Field key = MainActivity.class.getDeclaredField("pageKey");
                      key.setAccessible(true);
                      key.set(activity, "");
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                    call("render");
                  });
              startPractice(false);
              click("nav.Gear");
              click("nav.Crew");
              click("nav.Mission");
              screenshot("heist-large-text");
              main(() -> call("closeSession"));
            } finally {
              main(
                  () -> {
                    activity
                        .getResources()
                        .updateConfiguration(original, activity.getResources().getDisplayMetrics());
                    try {
                      Field key = MainActivity.class.getDeclaredField("pageKey");
                      key.setAccessible(true);
                      key.set(activity, "");
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                    call("render");
                  });
            }
          });
      test(
          "Camera denial, permission and cancellation preserve the live session",
          () -> {
            startPractice(false);
            Session active = session();
            main(() -> call("startScanner"));
            SystemClock.sleep(400);
            AccessibilityNodeInfo root = runner.getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo deny = find(root, "Don?t allow");
            if (deny == null) deny = find(root, "Don't allow");
            if (deny != null) {
              deny.performAction(AccessibilityNodeInfo.ACTION_CLICK);
              SystemClock.sleep(350);
              main(
                  () ->
                      assertTrue(
                          get("scanner") == null && session() == active,
                          "Denial changed the session"));
              main(() -> call("startScanner"));
            }
            long permissionDeadline = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < permissionDeadline) {
              AccessibilityNodeInfo allow =
                  find(runner.getUiAutomation().getRootInActiveWindow(), "While using the app");
              if (allow != null) {
                allow.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                break;
              }
              if (get("scanner") != null) break;
              SystemClock.sleep(100);
            }
            waitFor(() -> get("scanner") != null, 6000, "Scanner did not open");
            waitFor(
                () ->
                    ((com.journeyapps.barcodescanner.DecoratedBarcodeView) get("scanner"))
                        .getBarcodeView()
                        .isPreviewActive(),
                6000,
                "Camera preview did not become active");
            int[] before = {0};
            main(() -> before[0] = game().elapsedMs);
            SystemClock.sleep(1200);
            main(
                () ->
                    assertTrue(
                        session() == active && game().elapsedMs > before[0],
                        "Opening the embedded camera suspended the session"));
            AccessibilityNodeInfo cancel =
                find(runner.getUiAutomation().getRootInActiveWindow(), "Cancel scanning");
            assertTrue(cancel != null, "Scanner cancel missing");
            cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            waitFor(() -> get("scanner") == null, 2000, "Scanner did not cancel");
            main(() -> assertTrue(session() == active, "Cancel replaced the live session"));
            main(() -> call("closeSession"));
          });
      File profile =
          new File(
              runner.getTargetContext().getExternalFilesDir(null), "heist-smoke/heist-gfxinfo.txt");
      try (FileWriter writer = new FileWriter(profile)) {
        writer.write(shellOutput("dumpsys gfxinfo com.ghostprotocol.app framestats"));
      }
      Bundle results = new Bundle();
      results.putString("stream", "\nGhostProtocol heist: PASS (" + passed + " scenarios)\n");
      runner.finish(Activity.RESULT_OK, results);
    } catch (Throwable error) {
      try {
        screenshot("heist-failure");
      } catch (Exception ignored) {
      }
      StringWriter trace = new StringWriter();
      error.printStackTrace(new PrintWriter(trace));
      Bundle result = new Bundle();
      result.putString("stream", "\nGhostProtocol heist: FAIL\n" + trace);
      runner.finish(Activity.RESULT_CANCELED, result);
    }
  }
}
