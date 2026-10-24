package com.ghostprotocol.app;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import com.ghostprotocol.core.Game;
import java.io.*;
import java.util.*;

/** Live accessibility tests, without waiting for a real-time game to become idle. */
public final class SmokeInstrumentation extends Instrumentation {
  private int passed;
  private MainActivity activity;
  private Bundle arguments;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args;
    start();
  }

  @Override
  public void onStart() {
    if (arguments != null && "mesh".equals(arguments.getString("mode"))) {
      new MeshScenario(this, arguments).run();
      return;
    }
    try {
      Intent launch =
          new Intent(getTargetContext(), MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      getUiAutomation();
      activity = (MainActivity) startActivitySync(launch);
      SystemClock.sleep(700);
      test(
          "Hunter role and calibration",
          () -> {
            startTraining("Play hunter");
            expect("CALIBRATION");
          });
      test(
          "Timer stays responsive during callback bursts",
          () -> {
            Handler main = new Handler(Looper.getMainLooper());
            java.util.concurrent.atomic.AtomicBoolean flooding =
                new java.util.concurrent.atomic.AtomicBoolean(true);
            var schedule = MainActivity.class.getDeclaredMethod("scheduleRender");
            schedule.setAccessible(true);
            var timerField = MainActivity.class.getDeclaredField("liveTimer");
            timerField.setAccessible(true);
            String[] before = {""}, after = {""};
            runOnMainSync(
                () -> {
                  try {
                    before[0] =
                        ((android.widget.TextView) timerField.get(activity)).getText().toString();
                  } catch (Exception e) {
                    throw new AssertionError(e);
                  }
                });
            Runnable burst =
                new Runnable() {
                  public void run() {
                    if (!flooding.get()) return;
                    try {
                      schedule.invoke(activity);
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                    main.postDelayed(this, 10);
                  }
                };
            main.post(burst);
            try {
              SystemClock.sleep(2300);
              runOnMainSync(
                  () -> {
                    try {
                      after[0] =
                          ((android.widget.TextView) timerField.get(activity)).getText().toString();
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                  });
              if (before[0].equals(after[0]))
                throw new AssertionError("Live timer stalled during callbacks");
            } finally {
              flooding.set(false);
              main.removeCallbacks(burst);
            }
          });
      test(
          "Signal, people and evidence graph",
          () -> {
            SystemClock.sleep(10000);
            click("PEOPLE", true);
            expect("Everyone is a suspect.");
            screenshot("people");
            click("GRAPH", true);
            expect("Follow the connections.");
            screenshot("graph");
            click("SIGNAL", true);
            expect("YOUR SIGNAL HISTORY");
            screenshot("signal");
          });
      test(
          "Training movement",
          () -> {
            click("Move area", false);
            expect("Center");
          });
      test(
          "Ghost role and private controls",
          () -> {
            scrollToTop();
            click("TRAINING", true);
            click("LEAVE", true);
            startTraining("Play ghost");
            click("PRIVATE ROLE TOOLS", false);
            expect("Ghost controls");
            expect("2 charges remaining");
            click("HIDE", true);
          });
      test(
          "Ghost ability after calibration",
          () -> {
            SystemClock.sleep(31000);
            click("PRIVATE ROLE TOOLS", false);
            click("USE FADE", true);
            click("PRIVATE ROLE TOOLS", false);
            expect("1 charge remaining");
            click("HIDE", true);
            screenshot("ghost-round");
          });
      test(
          "Echo target selection",
          () -> {
            runOnMainSync(
                () -> {
                  Game g = currentSession().game();
                  advance(g, 31000);
                  String target = g.players.get(1).id();
                  String error =
                      g.apply(Game.Event.observation(currentSession().self, 1, List.of(target)));
                  if (!error.isEmpty()) throw new AssertionError(error);
                });
            click("PRIVATE ROLE TOOLS", false);
            click("ECHO…", true);
            click("Mira", true);
            click("PRIVATE ROLE TOOLS", false);
            expect("0 charges remaining");
            click("HIDE", true);
          });
      test(
          "Accusation and full round debrief",
          () -> {
            scrollToTop();
            click("TRAINING", true);
            click("LEAVE", true);
            startTraining("Play hunter");
            runOnMainSync(
                () -> {
                  Session s = currentSession();
                  Game g = s.game();
                  advance(g, 30000);
                  String witness = g.players.get(1).id();
                  for (int interval = 0; interval < 3; interval++) {
                    String first = g.apply(Game.Event.observation(s.self, 2, List.of(g.ghost)));
                    String second = g.apply(Game.Event.observation(witness, 2, List.of(g.ghost)));
                    if (!first.isEmpty() || !second.isEmpty())
                      throw new AssertionError(first + second);
                    if (interval < 2) advance(g, 10000);
                  }
                });
            click("PEOPLE", true);
            clickTagFor("Noor");
            click("START ACCUSATION", true);
            expect("Ghost");
            expect("contained.");
            expect("HUNTERS WIN");
            screenshot("debrief");
          });
      Bundle results = new Bundle();
      results.putString("stream", "\nGhostProtocol smoke: PASS (" + passed + " scenarios)\n");
      finish(Activity.RESULT_OK, results);
    } catch (Throwable error) {
      Bundle b = new Bundle();
      StringWriter out = new StringWriter();
      error.printStackTrace(new PrintWriter(out));
      b.putString("stream", "\nGhostProtocol smoke: FAIL\n" + out);
      finish(Activity.RESULT_CANCELED, b);
    }
  }

  private interface Scenario {
    void run() throws Exception;
  }

  private Session currentSession() {
    try {
      var field = MainActivity.class.getDeclaredField("session");
      field.setAccessible(true);
      return (Session) field.get(activity);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private void advance(Game game, int milliseconds) {
    while (milliseconds > 0) {
      int step = Math.min(5000, milliseconds);
      game.apply(Game.Event.tick(step, game.ids()));
      milliseconds -= step;
    }
  }

  private void clickTagFor(String name) throws Exception {
    for (int attempt = 0; attempt < 6; attempt++) {
      AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow(),
          person = find(root, name, true);
      if (person != null) {
        AccessibilityNodeInfo row = person.getParent().getParent();
        AccessibilityNodeInfo tag = find(row, "TAG", false);
        if (tag != null && tag.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
          SystemClock.sleep(250);
          return;
        }
      }
      AccessibilityNodeInfo scroll = findClass(root, "android.widget.ScrollView");
      if (scroll != null) scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
      SystemClock.sleep(200);
    }
    throw new AssertionError("Cannot tag " + name);
  }

  private void scrollToTop() {
    for (int i = 0; i < 8; i++) {
      AccessibilityNodeInfo scroll =
          findClass(getUiAutomation().getRootInActiveWindow(), "android.widget.ScrollView");
      if (scroll == null || !scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        break;
      SystemClock.sleep(100);
    }
  }

  private void test(String name, Scenario action) throws Exception {
    Bundle status = new Bundle();
    status.putString("stream", "\nRUN " + name + "\n");
    sendStatus(1, status);
    action.run();
    passed++;
    Bundle done = new Bundle();
    done.putString("stream", "PASS " + name + "\n");
    sendStatus(0, done);
  }

  private void startTraining(String role) throws Exception {
    expect("Someone here");
    AccessibilityNodeInfo input =
        findClass(getUiAutomation().getRootInActiveWindow(), "android.widget.EditText");
    if (input == null) throw new AssertionError("Callsign input missing");
    Bundle value = new Bundle();
    value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Alex");
    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value);
    screenshot("home");
    click("Try a training room", false);
    click(role, true);
    expect("Your identity");
    click("Reveal my role", true);
    expect(role.equals("Play ghost") ? "YOU ARE THE GHOST" : "YOU ARE A HUNTER");
    click("HIDE MY ROLE", true);
    click("I've read it.", false);
  }

  private AccessibilityNodeInfo findClass(AccessibilityNodeInfo node, String cls) {
    if (node == null) return null;
    if (cls.contentEquals(node.getClassName())) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo found = findClass(node.getChild(i), cls);
      if (found != null) return found;
    }
    return null;
  }

  private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text, boolean exact) {
    if (node == null) return null;
    CharSequence value = node.getText();
    if (value != null) {
      String actual = value.toString().toLowerCase(Locale.ROOT),
          wanted = text.toLowerCase(Locale.ROOT);
      if ((exact ? actual.equals(wanted) : actual.contains(wanted)) && node.isVisibleToUser())
        return node;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo result = find(node.getChild(i), text, exact);
      if (result != null) return result;
    }
    return null;
  }

  private void click(String text, boolean exact) throws Exception {
    for (int attempt = 0; attempt < 40; attempt++) {
      AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow(),
          node = find(root, text, exact);
      if (node != null) {
        while (node != null && !node.isClickable()) node = node.getParent();
        if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
          SystemClock.sleep(250);
          return;
        }
      }
      if (attempt % 10 == 9) {
        AccessibilityNodeInfo scroll = findClass(root, "android.widget.ScrollView");
        if (scroll != null) scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
      }
      SystemClock.sleep(100);
    }
    throw new AssertionError("Cannot click: " + text);
  }

  private void expect(String text) {
    for (int i = 0; i < 40; i++) {
      if (find(getUiAutomation().getRootInActiveWindow(), text, false) != null) return;
      SystemClock.sleep(100);
    }
    throw new AssertionError("Expected visible text: " + text);
  }

  private void screenshot(String name) throws IOException {
    Bitmap bitmap = getUiAutomation().takeScreenshot();
    if (bitmap == null) return;
    File dir = new File(getTargetContext().getExternalFilesDir(null), "smoke");
    if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create screenshot directory");
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
  }
}
