package com.ghostprotocol.core;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ReplicaTest {
  private static final class Mesh {
    final Map<String, Replica> nodes = new LinkedHashMap<>();
    final ArrayDeque<byte[]> packets = new ArrayDeque<>();
    final Set<String> offline = new HashSet<>();
    final Set<String> dropOnce = new HashSet<>();

    Mesh() {
      this(4);
    }

    Mesh(int count) {
      List<Game.Player> players = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        String id = String.valueOf((char) ('a' + index));
        players.add(new Game.Player(id, id.toUpperCase(Locale.ROOT)));
      }
      Game genesis = new Game(players, players.get(count - 1).id(), 1);
      for (String id : genesis.ids())
        nodes.put(
            id,
            new Replica(
                id,
                "a",
                genesis.copy(),
                packets::add,
                new Replica.Listener() {
                  public void changed() {}

                  public void rejected(String a, String b) {}
                }));
    }

    void deliver() {
      int guard = 0;
      while (!packets.isEmpty()) {
        assertTrue("Protocol loop", guard++ < 10000);
        byte[] p = packets.remove();
        for (var n : nodes.entrySet())
          if (!offline.contains(n.getKey()) && !dropOnce.remove(type(p) + "@" + n.getKey()))
            n.getValue().receive(p);
      }
    }

    String type(byte[] packet) {
      try {
        var input = Wire.input(packet);
        input.readInt();
        return Wire.string(input);
      } catch (java.io.IOException error) {
        throw new AssertionError(error);
      }
    }

    void settle() {
      for (int attempt = 0; attempt < 5; attempt++) {
        deliver();
        for (Replica replica : nodes.values()) replica.heartbeat();
      }
      deliver();
    }
  }

  @Test
  public void allPhonesCommitIdenticalState() {
    Mesh m = new Mesh();
    Replica leader = m.nodes.get("a");
    leader.submit(Game.Event.tick(1000, leader.game.ids()));
    m.deliver();
    for (Replica r : m.nodes.values()) {
      assertEquals(1, r.revision);
      assertEquals(leader.digest(), r.digest());
      assertEquals(1000, r.game.elapsedMs);
    }
  }

  @Test
  public void absentMemberPreventsCommitThenRetransmissionRecovers() {
    Mesh m = new Mesh();
    m.offline.add("d");
    Replica leader = m.nodes.get("a");
    leader.submit(Game.Event.tick(1000, leader.game.ids()));
    m.deliver();
    assertEquals(0, leader.revision);
    m.offline.clear();
    leader.heartbeat();
    m.deliver();
    for (Replica r : m.nodes.values()) assertEquals(1, r.revision);
  }

  @Test
  public void rejectedEventsLeaveEveryReplicaUntouched() {
    Mesh m = new Mesh();
    Replica leader = m.nodes.get("a");
    leader.submit(Game.Event.tag("a", "d"));
    m.deliver();
    for (Replica r : m.nodes.values()) {
      assertEquals(0, r.revision);
      assertEquals(0, r.game.elapsedMs);
    }
  }

  @Test
  public void lostPlayerCommandRetriesAndAppliesOnce() {
    Mesh m = new Mesh();
    Replica leader = m.nodes.get("a"), player = m.nodes.get("b");
    for (int i = 0; i < 10; i++) {
      leader.submit(Game.Event.tick(1000, leader.game.ids()));
      m.deliver();
    }
    m.dropOnce.add("COMMAND@a");
    player.submit(Game.Event.observation("b", 2, List.of("d")));
    m.deliver();
    assertTrue(leader.game.observations.isEmpty());
    player.heartbeat();
    m.settle();
    for (Replica replica : m.nodes.values()) {
      assertEquals(1, replica.game.observations.size());
      assertEquals(leader.digest(), replica.digest());
    }
  }

  @Test
  public void droppedCommitAndAcknowledgementRecoverForEightPhones() {
    Mesh m = new Mesh(8);
    m.dropOnce.add("COMMIT@h");
    m.dropOnce.add("COMMITTED@a");
    Replica leader = m.nodes.get("a");
    leader.submit(Game.Event.tick(2000, leader.game.ids()));
    m.settle();
    for (Replica replica : m.nodes.values()) {
      assertEquals(1, replica.revision);
      assertEquals(leader.digest(), replica.digest());
    }
    assertFalse(leader.busy());
  }

  @Test
  public void lateCommitCannotReviveAnAbortedPhone() {
    Mesh m = new Mesh();
    m.dropOnce.add("COMMIT@d");
    Replica leader = m.nodes.get("a"), phone = m.nodes.get("d");
    leader.submit(Game.Event.tick(1000, leader.game.ids()));
    m.deliver();
    assertEquals(0, phone.revision);
    phone.abort("Connection timeout");
    leader.heartbeat();
    m.deliver();
    assertEquals(Game.Phase.ENDED, phone.game.phase);
    assertEquals("NONE", phone.game.winner);
  }

  @Test
  public void lateAcknowledgementsCannotReviveAnAbortedLeader() {
    Mesh m = new Mesh();
    m.offline.add("d");
    Replica leader = m.nodes.get("a");
    leader.submit(Game.Event.tick(1000, leader.game.ids()));
    m.deliver();
    leader.abort("Connection timeout");
    m.offline.clear();
    for (Replica replica : m.nodes.values()) replica.heartbeat();
    m.deliver();
    assertEquals(Game.Phase.ENDED, leader.game.phase);
    assertEquals("NONE", leader.game.winner);
    assertFalse(leader.busy());
  }

  @Test
  public void pendingPrepareRetriesOnlyItsCurrentAcknowledgement() {
    Mesh mesh = new Mesh();
    Replica leader = mesh.nodes.get("a"), follower = mesh.nodes.get("b");
    leader.submit(Game.Event.tick(1000, leader.game.ids()));
    mesh.deliver();
    mesh.offline.addAll(Set.of("c", "d"));
    leader.submit(Game.Event.tick(1000, leader.game.ids()));
    mesh.deliver();
    follower.heartbeat();
    assertEquals(1, mesh.packets.size());
    assertEquals("ACK", mesh.type(mesh.packets.peek()));
    mesh.offline.clear();
    mesh.settle();
    for (Replica replica : mesh.nodes.values()) assertEquals(2, replica.revision);
  }
}
