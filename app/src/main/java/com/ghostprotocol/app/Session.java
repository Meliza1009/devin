package com.ghostprotocol.app;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.*;
import com.ghostprotocol.core.*;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * Owns one in-memory room, including transport lifecycle, leases and role-safe presentation data.
 */
public final class Session implements AutoCloseable {
  public interface Listener {
    void changed();

    void message(String text);
  }

  public enum Screen {
    LOBBY,
    ROLE,
    ROUND,
    RESULTS
  }

  public static final class Member {
    public final String id;
    public String name;
    public long heardAt;
    public boolean ready;
    public boolean begun;

    Member(String id, String name, long now) {
      this.id = id;
      this.name = name;
      heardAt = now;
    }
  }

  private record Sample(long at, int rssi) {}

  private final Context context;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  public final String self = RoomCrypto.newId();
  public final String code;
  public final boolean host, training;
  private final RoomCrypto crypto;
  private BleMesh mesh;
  public Screen screen = Screen.LOBBY;
  public final TreeMap<String, Member> members = new TreeMap<>();
  private final Map<String, ArrayDeque<Sample>> signals = new HashMap<>();
  private final Set<String> readied = new HashSet<>();
  private boolean receiverRegistered;
  private final BroadcastReceiver radioState =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
          if (closed || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
          int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
          if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
            if (mesh != null) {
              mesh.close();
              mesh = null;
            }
            status = "Bluetooth is off. Turn it on to reconnect the room.";
            listener.changed();
          } else if (state == BluetoothAdapter.STATE_ON && !suspended) {
            if (mesh != null) mesh.close();
            openRadio();
            lastPulse = 0;
          }
        }
      };
  private Replica replica;
  private Game demoGame;
  private String leader = "";
  private boolean started, begun, closed, suspended;
  private long lastPulse, lastTick, lastSyncProgress, suspendAt, lastProposalAt;
  private int observedInterval = -1, demoZone;
  private long lastBotAction;
  private Game.Event pendingAction;
  public final Set<Game.Station> verifiedCheckpoints = EnumSet.noneOf(Game.Station.class);
  private final Map<String, Integer> submittedObservations = new HashMap<>();
  public String status = "Opening local radio…";

  public Session(
      Context context,
      String name,
      String code,
      boolean host,
      boolean training,
      boolean ghost,
      Listener listener) {
    this.context = context.getApplicationContext();
    this.listener = listener;
    this.code = RoomCrypto.normalize(code);
    this.host = host;
    this.training = training;
    crypto = new RoomCrypto(code);
    long now = SystemClock.elapsedRealtime();
    members.put(self, new Member(self, name, now));
    if (host) leader = self;
    lastTick = lastSyncProgress = now;
    if (training) {
      List<Game.Player> players = new ArrayList<>();
      players.add(new Game.Player(self, name));
      int j = 1;
      for (String n : List.of("Mira", "Jules", "Noor", "Eli", "Sage")) {
        String id = String.format(Locale.ROOT, "%016x", j++);
        members.put(id, new Member(id, n, now));
        players.add(new Game.Player(id, n));
      }
      demoGame =
          new Game(players, ghost ? self : players.get(3).id(), new SecureRandom().nextLong());
      screen = Screen.ROLE;
      started = true;
      status = "Training · five simulated players";
    } else {
      IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
      if (Build.VERSION.SDK_INT >= 33)
        this.context.registerReceiver(radioState, filter, Context.RECEIVER_EXPORTED);
      else this.context.registerReceiver(radioState, filter);
      receiverRegistered = true;
      openRadio();
    }
    handler.post(pulse);
  }

  public Game game() {
    return training ? demoGame : replica == null ? null : replica.game;
  }

  public long revision() {
    return replica == null ? 0 : replica.revision;
  }

  public boolean isGhost() {
    return game() != null && game().ghost.equals(self);
  }

  public boolean isLeader() {
    return leader.equals(self);
  }

  public boolean synchronizing() {
    return !training
        && replica != null
        && begun
        && SystemClock.elapsedRealtime() - lastSyncProgress > 6000;
  }

  public String zone() {
    return new String[] {"Vault", "Workshop", "Exit"}[demoZone];
  }

  public void move() {
    demoZone = (demoZone + 1) % 3;
    scanStation(Game.Station.values()[demoZone + 1]);
  }

  public boolean sending() {
    return pendingAction != null;
  }

  public String scanStation(Game.Station station) {
    if (screen == Screen.LOBBY) {
      verifiedCheckpoints.add(station);
      listener.changed();
      return "";
    }
    if (training) demoZone = station.ordinal() - 1;
    return act(Game.Event.scan(self, station));
  }

  public void practiceSendInstructions(String taskId) {
    if (!training) return;
    Game.Task task = game().tasks.get(taskId);
    if (task != null && task.reader.equals(self)) {
      game().apply(Game.Event.answer(task.operator, task.id, task.answerCode()));
      listener.changed();
    }
  }

  public void practicePartnerLeads() {
    if (!training || game().taskFor(self) != null) return;
    Game g = game();
    for (String id : g.ids())
      if (!id.equals(self) && !id.equals(g.ghost)) {
        g.apply(Game.Event.scan(id, Game.Station.VAULT));
        String error = g.apply(Game.Event.task(id, "BREACH", ""));
        if (!error.isEmpty()) listener.message(error);
        listener.changed();
        return;
      }
  }

  public List<Member> lobbyMembers() {
    long now = SystemClock.elapsedRealtime();
    List<Member> list = new ArrayList<>();
    for (Member m : members.values())
      if (m.id.equals(self) || now - m.heardAt < 10000 || started) list.add(m);
    return list;
  }

  public List<String> missing() {
    List<String> result = new ArrayList<>();
    if (training) return result;
    long now = SystemClock.elapsedRealtime();
    Game g = game();
    if (g != null)
      for (String id : g.ids())
        if (!id.equals(self)
            && (!members.containsKey(id) || now - members.get(id).heardAt >= 10000)) result.add(id);
    if (suspended && g != null) result.add(self);
    return result;
  }

  public boolean paused() {
    return !missing().isEmpty();
  }

  public int connectedLinks() {
    return mesh == null ? 0 : mesh.connectedCount();
  }

  public void startRound() {
    if (!host || started) return;
    if (verifiedCheckpoints.size() != 3) {
      listener.message(
          "Place and scan the Vault, Workshop, and Exit cards before assigning roles.");
      return;
    }
    List<Member> lobby = lobbyMembers();
    if (lobby.size() < 4 || lobby.size() > 8) {
      listener.message("Gather 4–8 phones to start.");
      return;
    }
    List<Game.Player> players = new ArrayList<>();
    for (Member m : lobby) players.add(new Game.Player(m.id, m.name));
    SecureRandom random = new SecureRandom();
    createReplica(
        new Game(players, players.get(random.nextInt(players.size())).id(), random.nextLong()),
        self);
    started = true;
    screen = Screen.ROLE;
    broadcastStart();
    listener.changed();
  }

  private void createReplica(Game game, String leader) {
    this.leader = leader;
    replica =
        new Replica(
            self,
            leader,
            game,
            b -> send("STATE", b),
            new Replica.Listener() {
              @Override
              public void changed() {
                lastSyncProgress = SystemClock.elapsedRealtime();
                if (pendingAction != null && game().hasApplied(pendingAction.requestId)) {
                  pendingAction = null;
                  listener.message("Action confirmed by the room.");
                }
                if (game().phase == Game.Phase.ENDED) screen = Screen.RESULTS;
                listener.changed();
              }

              @Override
              public void rejected(String actor, String requestId, String reason) {
                if (actor.equals(self)
                    && pendingAction != null
                    && pendingAction.requestId.equals(requestId)) {
                  pendingAction = null;
                  listener.message(reason);
                }
              }
            });
    lastSyncProgress = SystemClock.elapsedRealtime();
  }

  public void ready() {
    readied.add(self);
    members.get(self).ready = true;
    screen = Screen.ROUND;
    if (training) {
      begun = true;
      lastTick = SystemClock.elapsedRealtime();
    } else send("READY", new byte[0]);
    listener.changed();
  }

  public int readyCount() {
    return readied.size();
  }

  public boolean begun() {
    return begun;
  }

  public String act(Game.Event event) {
    Game g = game();
    if (g == null) return "A round has not started.";
    if (paused() || suspended) return "Wait for all phones to reconnect.";
    if (!begun) return "Wait until everyone has read their role.";
    if (pendingAction != null) return "Waiting for your previous action to be confirmed.";
    Game copy = g.copy();
    String error = copy.apply(event);
    if (!error.isEmpty()) return error;
    if (training) {
      g.apply(event);
      if (g.phase == Game.Phase.ENDED) screen = Screen.RESULTS;
      listener.changed();
    } else {
      if (synchronizing()) return "The room is synchronizing. Try again in a moment.";
      pendingAction = event;
      replica.submit(event);
      listener.message("Action sent to the room.");
    }
    return "";
  }

  private final Runnable pulse =
      new Runnable() {
        @Override
        public void run() {
          if (closed) return;
          long now = SystemClock.elapsedRealtime();
          if (training) {
            if (begun && game().phase != Game.Phase.ENDED && !suspended) {
              int dt = (int) Math.min(5000, now - lastTick);
              lastTick += dt;
              game().apply(Game.Event.tick(dt, game().ids()));
              simulate();
            }
          } else {
            if (now - lastPulse >= 2000 && !suspended) {
              lastPulse = now;
              sendHello();
              if (replica != null) {
                replica.heartbeat();
                if (host && !begun) broadcastStart();
                if (readied.contains(self) && !begun) send("READY", new byte[0]);
                if (host
                    && begun
                    && game().ids().stream()
                        .anyMatch(
                            id ->
                                !id.equals(self)
                                    && (!members.containsKey(id) || !members.get(id).begun)))
                  send("BEGIN", new byte[0]);
              }
            }
            if (host && started && !begun && readied.containsAll(game().ids())) {
              begun = true;
              lastTick = now;
              send("BEGIN", new byte[0]);
            }
            if (begun && game() != null && game().phase != Game.Phase.ENDED) {
              if (!missing().isEmpty() || suspended) {
                lastTick = now;
                long oldest = now;
                for (String id : missing()) {
                  Member m = members.get(id);
                  oldest =
                      Math.min(
                          oldest,
                          id.equals(self) ? suspendAt : m == null ? now - 70000 : m.heardAt);
                }
                if (now - oldest >= 70000) {
                  replica.abort("A phone did not return. This round has no winner.");
                  screen = Screen.RESULTS;
                }
              } else if (isLeader() && !replica.busy() && now - lastTick >= 2000) {
                int dt = (int) Math.min(5000, now - lastTick);
                lastTick += dt;
                replica.submit(Game.Event.tick(dt, game().ids()));
              }
              if (!paused()
                  && !suspended
                  && game().elapsedMs >= 10000
                  && observedInterval != game().interval()
                  && !replica.busy()
                  && now - lastProposalAt > 2000) {
                observedInterval = game().interval();
                lastProposalAt = now;
                replica.submit(observation(self));
              }
            }
          }
          if (game() != null && game().phase == Game.Phase.ENDED) screen = Screen.RESULTS;
          listener.changed();
          handler.postDelayed(this, 500);
        }
      };

  private void sendHello() {
    send(
        "HELLO",
        Wire.bytes(
            o -> {
              Wire.string(o, members.get(self).name);
              o.writeBoolean(host);
              o.writeBoolean(started);
              o.writeBoolean(begun);
            }));
  }

  private void broadcastStart() {
    if (replica != null) send("START", replica.game.encode());
  }

  private void send(String type, byte[] body) {
    if (mesh != null && !suspended)
      mesh.send(
          Wire.bytes(
              o -> {
                o.writeInt(Game.VERSION);
                Wire.string(o, type);
                Wire.string(o, self);
                Wire.blob(o, body);
              }));
  }

  private void receive(byte[] packet) {
    try {
      DataInputStream i = Wire.input(packet);
      if (i.readInt() != Game.VERSION) {
        status = "Version mismatch. Install Haunted Heist 2.0 on every phone.";
        listener.changed();
        return;
      }
      String type = Wire.string(i), sender = Wire.string(i);
      byte[] body = Wire.blob(i, 120000);
      if (i.available() != 0 || sender.equals(self) || !sender.matches("[0-9a-f]{16}")) return;
      if (started && !game().ids().contains(sender)) return;
      long now = SystemClock.elapsedRealtime();
      if (!started)
        members
            .entrySet()
            .removeIf(e -> !e.getKey().equals(self) && now - e.getValue().heardAt > 15000);
      Member member = members.get(sender);
      switch (type) {
        case "HELLO":
          {
            DataInputStream h = Wire.input(body);
            String name = Wire.string(h);
            boolean isHost = h.readBoolean();
            h.readBoolean();
            boolean peerBegun = h.available() > 0 && h.readBoolean();
            if (name.isBlank() || name.length() > 24) return;
            if (!members.containsKey(sender) && members.size() >= 16) return;
            if (member == null) {
              member = new Member(sender, name, now);
              members.put(sender, member);
            } else member.name = name;
            member.heardAt = now;
            member.begun = peerBegun;
            if (isHost && !host && (leader.isEmpty() || leader.equals(sender))) leader = sender;
            if (started && sender.equals(leader) && peerBegun && !begun) {
              begun = true;
              lastTick = now;
            }
            break;
          }
        case "START":
          if (!host && !started && sender.equals(leader)) {
            Game g = Game.decode(body);
            if (!g.ids().contains(self)) return;
            createReplica(g, sender);
            started = true;
            screen = Screen.ROLE;
            for (Game.Player p : g.players)
              members.putIfAbsent(p.id(), new Member(p.id(), p.name(), now));
          }
          break;
        case "READY":
          if (started) {
            readied.add(sender);
            if (member != null) member.ready = true;
          }
          break;
        case "BEGIN":
          if (started && sender.equals(leader) && !begun) {
            begun = true;
            lastTick = now;
          }
          break;
        case "STATE":
          if (replica != null) replica.receive(body);
          break;
        default:
          return;
      }
      listener.changed();
    } catch (IOException | IllegalArgumentException ignored) {
    }
  }

  private void openRadio() {
    mesh =
        new BleMesh(
            context,
            self,
            crypto,
            new BleMesh.Listener() {
              @Override
              public void packet(byte[] plain) {
                receive(plain);
              }

              @Override
              public void signal(String peer, int rssi) {
                long now = SystemClock.elapsedRealtime();
                ArrayDeque<Sample> list = signals.computeIfAbsent(peer, k -> new ArrayDeque<>());
                list.add(new Sample(now, rssi));
                while (!list.isEmpty() && now - list.peek().at > 10000) list.remove();
              }

              @Override
              public void status(String text) {
                status = text;
                listener.changed();
              }
            });
    try {
      mesh.start();
    } catch (SecurityException | IllegalStateException e) {
      status = "Bluetooth permission or adapter unavailable. Leave and enable it in Settings.";
      listener.changed();
    }
  }

  private int strength(String observer, String other) {
    if (training) {
      int index = 0;
      for (Game.Player p : game().players) {
        if (p.id().equals(other)) break;
        index++;
      }
      int ownIndex = 0;
      for (Game.Player p : game().players) {
        if (p.id().equals(observer)) break;
        ownIndex++;
      }
      double t = game().elapsedMs / 1000.0;
      double a = observer.equals(self) ? demoZone * 2.1 : Math.sin(t / 17 + ownIndex * 1.7) * 2.7;
      double b = other.equals(self) ? demoZone * 2.1 : Math.sin(t / 17 + index * 1.7) * 2.7;
      double d = Math.abs(a - b);
      return d < 1.5 ? 2 : d < 3.3 ? 1 : 0;
    }
    ArrayDeque<Sample> list = signals.get(other);
    if (list == null) return 0;
    long now = SystemClock.elapsedRealtime();
    List<Integer> values = new ArrayList<>();
    for (Sample s : list) if (now - s.at < 10000) values.add(s.rssi);
    if (values.isEmpty()) return 0;
    Collections.sort(values);
    int median = values.get(values.size() / 2);
    return median >= -63 ? 2 : median >= -79 ? 1 : 0;
  }

  private Game.Event observation(String observer) {
    Game g = game();
    List<String> near = new ArrayList<>();
    for (String id : g.ids()) if (!id.equals(observer) && strength(observer, id) > 0) near.add(id);
    String source = g.ghost;
    if (observer.equals(source)) {
      List<String> others = new ArrayList<>(g.ids());
      others.remove(source);
      source = others.get(Math.floorMod((int) g.seed, others.size()));
    }
    int band = strength(observer, source);
    if (g.roomEvent.equals("BLACKOUT") && g.eventUntil > g.elapsedMs) band = 0;
    int noise = Math.floorMod(Objects.hash(g.seed, observer, g.interval()), 7);
    if (noise == 0) band = Math.max(0, band - 1);
    if (noise == 1) band = Math.min(2, band + 1);
    return Game.Event.observation(observer, band, near);
  }

  private void simulate() {
    Game g = game();
    if (g.elapsedMs >= 10000) {
      for (String id : g.ids()) {
        if (submittedObservations.getOrDefault(id, -1) != g.interval()) {
          // Practice partners follow your selected checkpoint. No radio readings are claimed.
          List<String> near = new ArrayList<>(g.ids());
          near.remove(id);
          g.apply(Game.Event.observation(id, observation(id).value, near));
          submittedObservations.put(id, g.interval());
        }
      }
    }
    if (g.vote != null) {
      for (String id : g.ids()) {
        if (!id.equals(self)
            && g.vote != null
            && !id.equals(g.vote.suspect)
            && !g.vote.ballots.containsKey(id)) g.apply(Game.Event.vote(id, true));
      }
    }
    if (g.phase == Game.Phase.ENDED || SystemClock.elapsedRealtime() - lastBotAction < 1500) return;
    lastBotAction = SystemClock.elapsedRealtime();
    Game.Task own = g.taskFor(self);
    if (own != null && !own.active()) {
      for (String id : g.ids())
        if (!id.equals(self) && !(g.exposed && id.equals(g.ghost)) && g.taskFor(id) == null) {
          g.apply(Game.Event.scan(own.operator, own.station));
          g.apply(Game.Event.scan(id, own.station));
          if (g.apply(Game.Event.join(id, own.id)).isEmpty()) break;
        }
    }
    for (Game.Offer offer : new ArrayList<>(g.offers.values())) {
      if (!offer.to().equals(self)) g.apply(Game.Event.accept(offer.to(), offer.relic()));
    }
    for (String id : g.ids())
      if (!id.equals(self) && !id.equals(g.ghost)) {
        if (g.seals.containsKey(id)) continue;
        if (!g.inventory(id).isEmpty()) {
          Game.Relic r = g.inventory(id).get(0);
          Game.Station destination =
              r.state == Game.RelicState.RAW ? Game.Station.WORKSHOP : Game.Station.EXIT;
          g.apply(Game.Event.scan(id, destination));
          g.apply(
              r.state == Game.RelicState.RAW
                  ? Game.Event.seal(id, r.id)
                  : Game.Event.deposit(id, r.id));
        }
      }
    if (isGhost()) {
      for (Game.Task task : new ArrayList<>(g.tasks.values())) {
        if (task.active() && g.elapsedMs - task.createdAt > 7000)
          g.apply(Game.Event.answer(task.operator, task.id, task.answerCode()));
      }
      if (g.tasks.isEmpty()) {
        List<String> crew = new ArrayList<>(g.ids());
        crew.remove(self);
        String operator = crew.get((g.elapsedMs / 30000) % crew.size()),
            reader = crew.get(((g.elapsedMs / 30000) + 1) % crew.size());
        Game.Station station = g.delivered() == 3 ? Game.Station.EXIT : Game.Station.VAULT;
        g.apply(Game.Event.scan(operator, station));
        g.apply(Game.Event.scan(reader, station));
        String kind =
            g.stationOpen(station) ? (g.delivered() == 3 ? "EXTRACT" : "BREACH") : "REPAIR";
        if (g.apply(Game.Event.task(operator, kind, "")).isEmpty()) {
          Game.Task task = g.taskFor(operator);
          g.apply(Game.Event.join(reader, task.id));
        }
      }
    }
  }

  public void suspend() {
    if (closed || suspended) return;
    suspended = true;
    suspendAt = SystemClock.elapsedRealtime();
    if (mesh != null) {
      mesh.close();
      mesh = null;
    }
  }

  public void resume() {
    if (closed || !suspended) return;
    suspended = false;
    lastTick = SystemClock.elapsedRealtime();
    if (game() != null && lastTick - suspendAt >= 70000) {
      if (training) game().abort("This phone left the room for too long.");
      else replica.abort("This phone left the room for too long.");
      screen = Screen.RESULTS;
    }
    if (!training) openRadio();
  }

  @Override
  public void close() {
    closed = true;
    if (receiverRegistered) {
      context.unregisterReceiver(radioState);
      receiverRegistered = false;
    }
    handler.removeCallbacksAndMessages(null);
    if (mesh != null) mesh.close();
    signals.clear();
    members.clear();
    readied.clear();
    demoGame = null;
    replica = null;
  }
}
