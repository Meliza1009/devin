package com.ghostprotocol.core;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** Full objective flow through real prepare/commit replication, including loss and duplicates. */
public class HeistReplicaTest {
  static final class Mesh {
    final Map<String, Replica> nodes = new LinkedHashMap<>();
    final ArrayDeque<byte[]> packets = new ArrayDeque<>();
    boolean dropCommit;
    final List<String> errors = new ArrayList<>();

    Mesh(int count) {
      List<Game.Player> players = new ArrayList<>();
      for (int n = 0; n < count; n++) players.add(new Game.Player("p" + n, "Player " + n));
      Game g = new Game(players, "p" + (count - 1), 42);
      for (String id : g.ids())
        nodes.put(
            id,
            new Replica(
                id,
                "p0",
                g.copy(),
                packets::add,
                new Replica.Listener() {
                  public void changed() {}

                  public void rejected(String actor, String request, String reason) {
                    errors.add(reason);
                  }
                }));
    }

    Game game() {
      return nodes.get("p0").game;
    }

    void deliver() throws Exception {
      int guard = 0;
      while (!packets.isEmpty()) {
        assertTrue(guard++ < 20000);
        byte[] p = packets.remove();
        var input = Wire.input(p);
        input.readInt();
        String type = Wire.string(input);
        for (var entry : nodes.entrySet()) {
          if (dropCommit
              && type.equals("COMMIT")
              && entry.getKey().equals("p" + (nodes.size() - 1))) {
            dropCommit = false;
            continue;
          }
          entry.getValue().receive(p);
        }
      }
    }

    void apply(Game.Event event) throws Exception {
      nodes.get(event.type.equals("TICK") ? "p0" : event.actor).submit(event);
      for (int n = 0; n < 5; n++) {
        deliver();
        for (Replica r : nodes.values()) r.heartbeat();
      }
      deliver();
      assertTrue(errors.toString(), errors.isEmpty());
      String digest = nodes.get("p0").digest();
      for (Replica r : nodes.values()) {
        assertEquals(digest, r.digest());
        assertFalse(r.busy());
      }
    }

    void advance(int seconds) throws Exception {
      while (seconds > 0) {
        int step = Math.min(seconds, 5);
        apply(Game.Event.tick(step * 1000, game().ids()));
        seconds -= step;
      }
    }

    void task(Game.Station station, String kind) throws Exception {
      apply(Game.Event.scan("p0", station));
      apply(Game.Event.scan("p1", station));
      if (!game().recentlyNear("p0", "p1")) apply(Game.Event.observation("p0", 1, List.of("p1")));
      apply(Game.Event.task("p0", kind, ""));
      apply(Game.Event.join("p1", game().taskFor("p0").id));
    }

    void solve(boolean duplicate) throws Exception {
      Game.Task t = game().taskFor("p0");
      Game.Event answer = Game.Event.answer("p0", t.id, t.answerCode());
      apply(answer);
      if (duplicate) apply(answer);
    }

    void heist() throws Exception {
      advance(10);
      for (int n = 0; n < 3; n++) {
        task(Game.Station.VAULT, "BREACH");
        dropCommit = true;
        solve(true);
        assertEquals(1, game().inventory("p0").size());
        String relic = game().inventory("p0").get(0).id;
        apply(Game.Event.scan("p0", Game.Station.WORKSHOP));
        apply(Game.Event.seal("p0", relic));
        advance(6);
        apply(Game.Event.scan("p0", Game.Station.EXIT));
        apply(Game.Event.deposit("p0", relic));
      }
      task(Game.Station.EXIT, "EXTRACT");
      solve(false);
      for (Replica r : nodes.values()) {
        assertEquals("HUNTERS", r.game.winner);
        assertEquals(3, r.game.delivered());
      }
    }
  }

  @Test
  public void fourReplicasFinishAHeistWithLostCommitAndDuplicateReward() throws Exception {
    new Mesh(4).heist();
  }

  @Test
  public void eightReplicasFinishAHeistWithLostCommitAndDuplicateReward() throws Exception {
    new Mesh(8).heist();
  }
}
