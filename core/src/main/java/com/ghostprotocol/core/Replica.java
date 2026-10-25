package com.ghostprotocol.core;

import java.io.*;
import java.util.*;

/**
 * All-member prepare/ack/commit replication. A phone sequences events, but every phone stores
 * state.
 */
public final class Replica {
  public interface Network {
    void send(byte[] packet);
  }

  public interface Listener {
    void changed();

    void rejected(String actor, String requestId, String reason);
  }

  private final String self, leader;
  private final Network network;
  private final Listener listener;
  public Game game;
  public long revision;
  private Game pending;
  private Game.Event pendingEvent;
  private long pendingRevision;
  private String pendingDigest = "";
  private final Map<String, byte[]> outgoingCommands = new LinkedHashMap<>();
  private final Set<String> acknowledgements = new TreeSet<>();
  private final Set<String> seenCommands = new LinkedHashSet<>();
  private final Map<String, byte[]> rejectedCommands = new LinkedHashMap<>();
  private final ArrayDeque<Command> queue = new ArrayDeque<>();

  private record Command(String id, Game.Event event) {}

  private byte[] preparePacket, commitPacket;
  private boolean waitingForCommitAcks;
  private boolean terminated;
  private final Set<String> commitAcks = new TreeSet<>();

  public Replica(String self, String leader, Game genesis, Network network, Listener listener) {
    this.self = self;
    this.leader = leader;
    this.game = genesis;
    this.network = network;
    this.listener = listener;
  }

  public boolean isLeader() {
    return self.equals(leader);
  }

  public boolean busy() {
    return pending != null || waitingForCommitAcks;
  }

  public String digest() {
    return RoomCrypto.hex(RoomCrypto.hash(game.encode()));
  }

  public void submit(Game.Event event) {
    if (terminated) {
      listener.rejected(event.actor, event.requestId, "This round has ended.");
      return;
    }
    if (outgoingCommands.size() >= 16) {
      listener.rejected(
          event.actor, event.requestId, "Wait for your previous actions to reach the room.");
      return;
    }
    String id = RoomCrypto.newId();
    byte[] packet =
        packet(
            "COMMAND",
            o -> {
              Wire.string(o, id);
              Wire.blob(o, event.encode());
            });
    outgoingCommands.put(id, packet);
    network.send(packet);
    receive(packet);
  }

  public void heartbeat() {
    if (terminated) return;
    for (byte[] command : new ArrayList<>(outgoingCommands.values())) network.send(command);
    if (isLeader()) {
      if (waitingForCommitAcks && commitPacket != null) network.send(commitPacket);
      else if (preparePacket != null) network.send(preparePacket);
      else drain();
    } else if (pending != null) sendAck("ACK", pendingRevision, pendingDigest);
    if (!isLeader() && pending == null && revision > 0) sendAck("COMMITTED", revision, digest());
  }

  public void receive(byte[] packet) {
    if (terminated) return;
    try {
      DataInputStream i = Wire.input(packet);
      if (i.readInt() != Game.VERSION) return;
      String type = Wire.string(i), sender = Wire.string(i);
      if (!game.ids().contains(sender)) return;
      switch (type) {
        case "COMMAND":
          {
            String id = Wire.string(i);
            Game.Event e = Game.Event.decode(Wire.blob(i, 4096));
            if (!isLeader()
                || (!e.type.equals("TICK") && !sender.equals(e.actor))
                || (e.type.equals("TICK") && !sender.equals(leader))) return;
            if (seenCommands.contains(id)) {
              byte[] outcome = rejectedCommands.get(id);
              if (outcome != null) network.send(outcome);
              return;
            }
            if (queue.size() >= 64) return;
            seenCommands.add(id);
            while (seenCommands.size() > 2048) {
              String oldest = seenCommands.iterator().next();
              seenCommands.remove(oldest);
              rejectedCommands.remove(oldest);
            }
            queue.add(new Command(id, e));
            drain();
            break;
          }
        case "PREPARE":
          {
            if (!sender.equals(leader) || isLeader()) return;
            long rev = i.readLong();
            String commandId = Wire.string(i), base = Wire.string(i), hash = Wire.string(i);
            Game.Event e = Game.Event.decode(Wire.blob(i, 4096));
            if (rev == revision) {
              sendAck("COMMITTED", revision, digest());
              return;
            }
            if (rev != revision + 1 || !base.equals(digest())) return;
            Game candidate = game.copy();
            if (!candidate.apply(e).isEmpty()
                || !hash.equals(RoomCrypto.hex(RoomCrypto.hash(candidate.encode())))) return;
            outgoingCommands.remove(commandId);
            pending = candidate;
            pendingEvent = e;
            pendingRevision = rev;
            pendingDigest = hash;
            sendAck("ACK", rev, hash);
            break;
          }
        case "ACK":
          {
            long rev = i.readLong();
            String hash = Wire.string(i);
            if (isLeader()
                && pending != null
                && rev == pendingRevision
                && hash.equals(pendingDigest)) {
              acknowledgements.add(sender);
              if (acknowledgements.containsAll(game.ids())) commit();
            }
            break;
          }
        case "COMMIT":
          {
            if (!sender.equals(leader) || isLeader()) return;
            long rev = i.readLong();
            String hash = Wire.string(i);
            if (rev == revision && hash.equals(digest())) {
              sendAck("COMMITTED", revision, hash);
              return;
            }
            if (pending != null && rev == pendingRevision && hash.equals(pendingDigest)) {
              game = pending;
              pending = null;
              revision = rev;
              sendAck("COMMITTED", revision, hash);
              listener.changed();
            }
            break;
          }
        case "COMMITTED":
          {
            long rev = i.readLong();
            String hash = Wire.string(i);
            if (isLeader() && waitingForCommitAcks && rev == revision && hash.equals(digest())) {
              commitAcks.add(sender);
              if (commitAcks.containsAll(game.ids())) {
                waitingForCommitAcks = false;
                commitPacket = null;
                drain();
              }
            }
            break;
          }
        case "REJECT":
          if (sender.equals(leader)) {
            String commandId = Wire.string(i),
                actor = Wire.string(i),
                requestId = Wire.string(i),
                reason = Wire.string(i);
            if (outgoingCommands.remove(commandId) != null)
              listener.rejected(actor, requestId, reason);
          }
          break;
        default:
          break;
      }
    } catch (IOException | IllegalArgumentException ignored) {
      /* malformed peer data is discarded */
    }
  }

  private void drain() {
    if (!isLeader() || busy() || queue.isEmpty()) return;
    Command command = queue.remove();
    Game candidate = game.copy();
    String error = candidate.apply(command.event);
    outgoingCommands.remove(command.id);
    if (!error.isEmpty()) {
      byte[] outcome =
          packet(
              "REJECT",
              o -> {
                Wire.string(o, command.id);
                Wire.string(o, command.event.actor);
                Wire.string(o, command.event.requestId);
                Wire.string(o, error);
              });
      rejectedCommands.put(command.id, outcome);
      network.send(outcome);
      listener.rejected(command.event.actor, command.event.requestId, error);
      drain();
      return;
    }
    pending = candidate;
    pendingEvent = command.event;
    pendingRevision = revision + 1;
    pendingDigest = RoomCrypto.hex(RoomCrypto.hash(candidate.encode()));
    acknowledgements.clear();
    acknowledgements.add(self);
    preparePacket =
        packet(
            "PREPARE",
            o -> {
              o.writeLong(pendingRevision);
              Wire.string(o, command.id);
              Wire.string(o, digest());
              Wire.string(o, pendingDigest);
              Wire.blob(o, pendingEvent.encode());
            });
    network.send(preparePacket);
  }

  /** Timeout is terminal even if a previously prepared commit arrives after reconnection. */
  public void abort(String reason) {
    if (terminated) return;
    terminated = true;
    game.abort(reason);
    pending = null;
    queue.clear();
    outgoingCommands.clear();
    rejectedCommands.clear();
    acknowledgements.clear();
    commitAcks.clear();
    preparePacket = null;
    commitPacket = null;
    waitingForCommitAcks = false;
    listener.changed();
  }

  private void commit() {
    game = pending;
    pending = null;
    revision = pendingRevision;
    preparePacket = null;
    acknowledgements.clear();
    waitingForCommitAcks = true;
    commitAcks.clear();
    commitAcks.add(self);
    commitPacket =
        packet(
            "COMMIT",
            o -> {
              o.writeLong(revision);
              Wire.string(o, digest());
            });
    network.send(commitPacket);
    listener.changed();
  }

  private void sendAck(String type, long rev, String digest) {
    network.send(
        packet(
            type,
            o -> {
              o.writeLong(rev);
              Wire.string(o, digest);
            }));
  }

  private byte[] packet(String type, Wire.Writer writer) {
    return Wire.bytes(
        o -> {
          o.writeInt(Game.VERSION);
          Wire.string(o, type);
          Wire.string(o, self);
          writer.write(o);
        });
  }
}
