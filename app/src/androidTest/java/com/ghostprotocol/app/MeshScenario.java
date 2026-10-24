package com.ghostprotocol.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import com.ghostprotocol.core.Game;
import com.ghostprotocol.core.RoomCrypto;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Runs the production Session and BLE adapter against other actual Android runtimes. */
public final class MeshScenario {
  private final Instrumentation instrumentation;
  private Bundle arguments;
  private Session session;
  private int expected;
  private boolean host, lobbyOnly;
  private String expectedWinner;
  private int holdResultMs;
  private String name, code, lastMessage = "";
  private boolean fadeUsed, echoUsed, nominationSent;
  private long lastAttempt;
  private int peakLinks;

  public MeshScenario(Instrumentation instrumentation, Bundle arguments) {
    this.instrumentation = instrumentation;
    this.arguments = arguments;
  }

  public void run() {
    try {
      expected = Integer.parseInt(arguments.getString("expected", "4"));
      host = Boolean.parseBoolean(arguments.getString("host", "false"));
      lobbyOnly = Boolean.parseBoolean(arguments.getString("lobby", "false"));
      expectedWinner = arguments.getString("winner", "HUNTERS");
      holdResultMs = Integer.parseInt(arguments.getString("holdResultMs", "5000"));
      name = arguments.getString("name", "Peer");
      code = arguments.getString("code");
      new RoomCrypto(code);
      Activity activity =
          instrumentation.startActivitySync(
              new Intent(instrumentation.getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      instrumentation.runOnMainSync(
          () -> {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            session =
                new Session(
                    instrumentation.getTargetContext(),
                    name,
                    code,
                    host,
                    false,
                    false,
                    new Session.Listener() {
                      public void changed() {}

                      public void message(String message) {
                        lastMessage = message;
                      }
                    });
          });
      long began = SystemClock.elapsedRealtime(), lastReport = 0, stableSince = 0, endedAt = 0;
      while (SystemClock.elapsedRealtime() - began < 240000) {
        long now = SystemClock.elapsedRealtime();
        instrumentation.runOnMainSync(this::act);
        JSONObject state = snapshot();
        peakLinks = Math.max(peakLinks, state.getInt("links"));
        state.put("peak_links", peakLinks);
        if (peakLinks > expected - 1) throw new AssertionError("Duplicate peer links: " + state);
        if (now - lastReport >= 5000) {
          report("RADIO_PROGRESS " + state);
          lastReport = now;
        }
        if (lobbyOnly) {
          if (state.getInt("members") == expected && state.getInt("links") > 0) {
            if (stableSince == 0) stableSince = now;
            if (now - stableSince >= 5000) {
              finishSuccess(state);
              return;
            }
          } else stableSince = 0;
        } else if (state.optString("phase").equals("ENDED")) {
          if (!state.optString("winner").equals(expectedWinner))
            throw new AssertionError("Unexpected end: " + state);
          if (endedAt == 0) endedAt = now;
          if (now - endedAt >= holdResultMs) {
            finishSuccess(state);
            return;
          }
        }
        SystemClock.sleep(250);
      }
      throw new AssertionError("Radio test timed out: " + snapshot());
    } catch (Throwable error) {
      Bundle result = new Bundle();
      result.putString("stream", "RADIO_FAILURE " + name + " " + error + "\n");
      instrumentation.finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (session != null) instrumentation.runOnMainSync(() -> session.close());
    }
  }

  private void act() {
    if (lobbyOnly) return;
    if (host && session.screen == Session.Screen.LOBBY && session.lobbyMembers().size() == expected)
      session.startRound();
    if (session.screen == Session.Screen.ROLE) session.ready();
    Game game = session.game();
    if (game == null
        || game.phase != Game.Phase.HUNT
        || session.paused()
        || session.synchronizing()) return;
    long now = SystemClock.elapsedRealtime();
    if (now - lastAttempt < 2500) return;
    if (game.vote != null
        && !game.vote.suspect.equals(session.self)
        && !game.vote.ballots.containsKey(session.self)) {
      lastAttempt = now;
      session.act(Game.Event.vote(session.self, true));
      return;
    }
    if (session.isGhost()) {
      if (!fadeUsed && game.elapsedMs >= 35000) {
        lastAttempt = now;
        fadeUsed = session.act(Game.Event.ability(session.self, "FADE", "")).isEmpty();
      }
      if (fadeUsed && !echoUsed && game.elapsedMs >= 68000) {
        for (Game.Player player : game.players)
          if (!player.id().equals(session.self) && game.recentlyNear(session.self, player.id())) {
            lastAttempt = now;
            echoUsed = session.act(Game.Event.ability(session.self, "ECHO", player.id())).isEmpty();
            break;
          }
      }
    }
    if (!nominationSent
        && game.elapsedMs >= 95000
        && game.vote == null
        && game.evidence(game.ghost).eligible()) {
      List<String> nominators = new ArrayList<>();
      for (String id : game.ids())
        if (!id.equals(game.ghost) && game.recentlyNear(id, game.ghost)) nominators.add(id);
      if (!nominators.isEmpty() && nominators.get(0).equals(session.self)) {
        lastAttempt = now;
        nominationSent = session.act(Game.Event.tag(session.self, game.ghost)).isEmpty();
      }
    }
  }

  private JSONObject snapshot() throws Exception {
    JSONObject[] result = {null};
    instrumentation.runOnMainSync(
        () -> {
          try {
            JSONObject state = new JSONObject();
            state.put("name", name);
            state.put("self", session.self);
            state.put("members", session.lobbyMembers().size());
            state.put("links", session.connectedLinks());
            state.put("screen", session.screen);
            state.put("status", session.status);
            state.put("message", lastMessage);
            state.put("ready", session.readyCount());
            state.put("revision", session.revision());
            state.put("missing", session.missing());
            Game game = session.game();
            if (game != null) {
              state.put("phase", game.phase);
              state.put("elapsed_ms", game.elapsedMs);
              state.put("winner", game.winner);
              state.put("digest", RoomCrypto.hex(RoomCrypto.hash(game.encode())));
              state.put("observations", game.observations.size());
              state.put("ghost", session.isGhost());
              state.put("fade_used", fadeUsed);
              state.put("echo_used", echoUsed);
            }
            result[0] = state;
          } catch (Exception error) {
            throw new IllegalStateException(error);
          }
        });
    return result[0];
  }

  private void report(String message) {
    Bundle status = new Bundle();
    status.putString("stream", message + "\n");
    instrumentation.sendStatus(0, status);
  }

  private void finishSuccess(JSONObject state) {
    Bundle result = new Bundle();
    result.putString("stream", "RADIO_RESULT " + state + "\n");
    instrumentation.finish(Activity.RESULT_OK, result);
  }
}
