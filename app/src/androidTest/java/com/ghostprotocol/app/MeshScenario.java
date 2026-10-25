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
  private boolean nominationSent;
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
      while (SystemClock.elapsedRealtime() - began < 480000) {
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

  private boolean used(Game game, String tool) {
    return game.timeline.stream()
        .anyMatch(e -> e.code().equals("SABOTAGE") && e.detail().startsWith(tool + " used"));
  }

  private boolean scan(Game.Station station) {
    if (session.game().visited(session.self, station)) return false;
    session.scanStation(station);
    return true;
  }

  private void act() {
    if (lobbyOnly) return;
    if (host
        && session.screen == Session.Screen.LOBBY
        && session.lobbyMembers().size() == expected) {
      session.verifiedCheckpoints.addAll(
          List.of(Game.Station.VAULT, Game.Station.WORKSHOP, Game.Station.EXIT));
      session.startRound();
    }
    if (session.screen == Session.Screen.ROLE) session.ready();
    Game g = session.game();
    if (g == null
        || !session.begun()
        || g.phase == Game.Phase.ENDED
        || session.paused()
        || session.synchronizing()
        || session.sending()) return;
    long now = SystemClock.elapsedRealtime();
    if (now - lastAttempt < 700) return;
    lastAttempt = now;
    if (g.vote != null
        && !g.vote.suspect.equals(session.self)
        && !g.vote.ballots.containsKey(session.self)) {
      session.act(Game.Event.vote(session.self, true));
      return;
    }
    if (session.isGhost()) {
      if (!used(g, "JAM") && g.elapsedMs >= 35000) {
        if (scan(Game.Station.VAULT)) return;
        session.act(Game.Event.ability(session.self, "JAM", "VAULT"));
        return;
      }
      if (used(g, "JAM") && !used(g, "CORRUPT") && !g.exposed) {
        for (Game.Relic relic : g.relics.values())
          if (relic.state == Game.RelicState.SEALED
              && !relic.owner.isEmpty()
              && relic.protectedUntil <= g.elapsedMs
              && g.recentlyNear(session.self, relic.owner)
              && g.charges >= 2
              && g.abilityReadyAt <= g.elapsedMs) {
            session.act(Game.Event.ability(session.self, "CORRUPT", relic.id));
            return;
          }
      }
      if (g.exposed && !used(g, "HAUNT") && g.abilityReadyAt <= g.elapsedMs) {
        for (String id : g.ids())
          if (!id.equals(session.self) && g.recentlyNear(session.self, id)) {
            session.act(Game.Event.ability(session.self, "HAUNT", id));
            return;
          }
      }
      return;
    }
    List<String> crew = new ArrayList<>(g.ids());
    crew.remove(g.ghost);
    String operator = crew.get(0), reader = crew.get(1);
    if (session.self.equals(operator)
        && g.elapsedMs >= 95000
        && used(g, "CORRUPT")
        && !g.exposed
        && g.vote == null
        && !nominationSent) {
      nominationSent = session.act(Game.Event.tag(session.self, g.ghost)).isEmpty();
      return;
    }
    Game.Task task = g.taskFor(session.self);
    if (task != null) {
      if (!task.active()) {
        if (!g.visited(session.self, task.station)) session.scanStation(task.station);
        return;
      }
      if (task.operator.equals(session.self)
          && g.agents.get(session.self).hauntedUntil <= g.elapsedMs
          && g.agents.get(task.reader).hauntedUntil <= g.elapsedMs)
        session.act(Game.Event.answer(session.self, task.id, task.answerCode()));
      return;
    }
    if (session.self.equals(reader)) {
      for (Game.Task waiting : g.tasks.values())
        if (waiting.operator.equals(operator) && !waiting.active()) {
          if (scan(waiting.station)) return;
          session.act(Game.Event.join(session.self, waiting.id));
          return;
        }
      return;
    }
    if (!session.self.equals(operator)
        || g.agents.get(session.self).hauntedUntil > g.elapsedMs
        || g.seals.containsKey(session.self)) return;
    if (!g.inventory(session.self).isEmpty()) {
      Game.Relic relic = g.inventory(session.self).get(0);
      if (relic.state == Game.RelicState.RAW) {
        if (scan(Game.Station.WORKSHOP)) return;
        if (!g.stationOpen(Game.Station.WORKSHOP)) return;
        session.act(Game.Event.seal(session.self, relic.id));
        return;
      }
      // Hold the first sealed relic until the test has observed real replicated corruption.
      if (!used(g, "CORRUPT")) return;
      if (scan(Game.Station.EXIT)) return;
      if (!g.stationOpen(Game.Station.EXIT)) return;
      session.act(Game.Event.deposit(session.self, relic.id));
      return;
    }
    Game.Station station = g.delivered() == 3 ? Game.Station.EXIT : Game.Station.VAULT;
    if (g.delivered() == 3 && (!g.exposed || !used(g, "HAUNT"))) return;
    if (scan(station)) return;
    if (!g.stationOpen(station)) {
      if (g.jamUntil.getOrDefault(station, 0) > g.elapsedMs)
        session.act(Game.Event.task(session.self, "REPAIR", ""));
      return;
    }
    session.act(Game.Event.task(session.self, g.delivered() == 3 ? "EXTRACT" : "BREACH", ""));
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
              state.put("jam_used", used(game, "JAM"));
              state.put("corrupt_used", used(game, "CORRUPT"));
              state.put("haunt_used", used(game, "HAUNT"));
              state.put("delivered", game.delivered());
              state.put("exposed", game.exposed);
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
