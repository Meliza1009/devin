package com.ghostprotocol.app;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.ScrollView;
import com.ghostprotocol.core.*;
import java.lang.reflect.*;
import java.util.*;

/** Reproduces touch replacement and inherited scroll using the real native activity. */
final class UiRegressionScenario {
  private final Instrumentation runner;
  private MainActivity activity;
  private final List<String> failures = new ArrayList<>();

  UiRegressionScenario(Instrumentation runner) {
    this.runner = runner;
  }

  private Object get(String name) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(activity);
  }

  private void invoke(String name) throws Exception {
    Method m = MainActivity.class.getDeclaredMethod(name);
    m.setAccessible(true);
    m.invoke(activity);
  }

  private void main(Runnable task) {
    runner.runOnMainSync(task);
  }

  void run() {
    try {
      activity =
          (MainActivity)
              runner.startActivitySync(
                  new Intent(runner.getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      main(
          () -> {
            try {
              Field name = MainActivity.class.getDeclaredField("callsign");
              name.setAccessible(true);
              name.set(activity, "Agent");
              Method open =
                  MainActivity.class.getDeclaredMethod(
                      "openSession", String.class, boolean.class, boolean.class, boolean.class);
              open.setAccessible(true);
              open.invoke(activity, RoomCrypto.newCode(), true, true, false);
              ((Session) get("session")).ready();
              invoke("render");
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      SystemClock.sleep(350);
      Session active = (Session) get("session");
      Field pulseField = Session.class.getDeclaredField("pulse"),
          sessionHandlerField = Session.class.getDeclaredField("handler");
      pulseField.setAccessible(true);
      sessionHandlerField.setAccessible(true);
      Runnable pulse = (Runnable) pulseField.get(active);
      Handler sessionHandler = (Handler) sessionHandlerField.get(active);
      int[] tapPoint = new int[2];
      main(() -> sessionHandler.removeCallbacks(pulse));
      for (String warmTab : List.of("Gear", "Mission")) {
        main(
            () -> {
              try {
                Field tab = MainActivity.class.getDeclaredField("tab");
                tab.setAccessible(true);
                tab.set(activity, warmTab);
                invoke("render");
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            });
        // Let the window actually measure and draw each page before measuring a cached switch.
        SystemClock.sleep(350);
      }
      main(
          () -> {
            try {
              ((Handler) get("handler")).removeCallbacks((Runnable) get("renderTask"));
              Field scheduled = MainActivity.class.getDeclaredField("renderScheduled");
              scheduled.setAccessible(true);
              scheduled.setBoolean(activity, false);
              View button = ((NativeUi) get("navUi")).view("nav.Gear");
              button.getLocationOnScreen(tapPoint);
              tapPoint[0] += button.getWidth() / 2;
              tapPoint[1] += button.getHeight() / 2;
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      long tapTime = SystemClock.uptimeMillis();
      MotionEvent
          navDown =
              MotionEvent.obtain(
                  tapTime, tapTime, MotionEvent.ACTION_DOWN, tapPoint[0], tapPoint[1], 0),
          navUp =
              MotionEvent.obtain(
                  tapTime, tapTime + 50, MotionEvent.ACTION_UP, tapPoint[0], tapPoint[1], 0);
      runner.sendPointerSync(navDown);
      long released = SystemClock.elapsedRealtime();
      runner.sendPointerSync(navUp);
      navDown.recycle();
      navUp.recycle();
      boolean[] navigated = {false};
      do {
        main(
            () -> {
              try {
                navigated[0] = "round/Gear".equals(get("pageKey"));
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            });
        if (!navigated[0]) SystemClock.sleep(2);
      } while (!navigated[0] && SystemClock.elapsedRealtime() - released < 300);
      long delay = SystemClock.elapsedRealtime() - released;
      Bundle latency = new Bundle();
      latency.putString("stream", "Warm navigation response: " + delay + "ms\n");
      runner.sendStatus(0, latency);
      if (!navigated[0] || delay >= 100)
        failures.add("Navigation waited " + delay + "ms after finger release (limit 100ms).");
      main(
          () -> {
            try {
              Field tab = MainActivity.class.getDeclaredField("tab");
              tab.setAccessible(true);
              tab.set(activity, "Mission");
              invoke("render");
              sessionHandler.post(pulse);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      SystemClock.sleep(350);
      main(
          () -> {
            try {
              ScrollView before = (ScrollView) get("scroll");
              long now = SystemClock.uptimeMillis();
              MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 160, 300, 0);
              activity.dispatchTouchEvent(down);
              down.recycle();
              Session s = (Session) get("session");
              s.game().apply(Game.Event.tick(5000, s.game().ids()));
              s.game().apply(Game.Event.tick(5000, s.game().ids()));
              s.game().apply(Game.Event.observation(s.self, 2, List.of(s.game().ghost)));
              invoke("render");
              if (get("scroll") != before)
                failures.add("A live update replaced the view while the finger was down.");
              MotionEvent up =
                  MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_CANCEL, 160, 300, 0);
              activity.dispatchTouchEvent(up);
              up.recycle();
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      SystemClock.sleep(350);
      main(
          () -> {
            try {
              ((ScrollView) get("scroll")).fullScroll(View.FOCUS_DOWN);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      SystemClock.sleep(350);
      main(
          () -> {
            try {
              if (((ScrollView) get("scroll")).getScrollY() <= 0)
                throw new AssertionError("Fixture must scroll before ending");
              Session s = (Session) get("session");
              s.game().abort("Regression fixture completed.");
              s.screen = Session.Screen.RESULTS;
              invoke("render");
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      SystemClock.sleep(350);
      main(
          () -> {
            try {
              if (((ScrollView) get("scroll")).getScrollY() != 0)
                failures.add("The result screen inherited the previous screen's scroll position.");
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      Bundle result = new Bundle();
      result.putString(
          "stream",
          "\nUI regression: "
              + (failures.isEmpty() ? "PASS (3 scenarios)" : "FAIL\n" + String.join("\n", failures))
              + "\n");
      runner.finish(failures.isEmpty() ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    } catch (Throwable e) {
      Bundle result = new Bundle();
      result.putString("stream", "\nUI regression: FAIL\n" + e + "\n");
      runner.finish(Activity.RESULT_CANCELED, result);
    }
  }
}
