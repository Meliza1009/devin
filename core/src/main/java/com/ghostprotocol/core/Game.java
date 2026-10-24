package com.ghostprotocol.core;

import java.io.*;
import java.util.*;

/** Deterministic round state. Radio, UI and wall clocks live outside this module. */
public final class Game {
  public enum Phase {
    CALIBRATING,
    HUNT,
    ENDED
  }

  public record Player(String id, String name) {}

  public record Observation(
      String observer, int interval, int atMs, int band, List<String> nearby) {}

  public record Evidence(int observers, int intervals) {
    public boolean eligible() {
      return observers >= 2 && intervals >= 3;
    }
  }

  public static final class Vote {
    public String suspect, initiator;
    public int endsAt;
    public final TreeMap<String, Boolean> ballots = new TreeMap<>();
  }

  public static final class Event {
    public String type, actor = "", target = "", ability = "";
    public int value;
    public List<String> peers = List.of();

    public static Event tick(int ms, Collection<String> online) {
      Event e = new Event();
      e.type = "TICK";
      e.value = ms;
      e.peers = new ArrayList<>(new TreeSet<>(online));
      return e;
    }

    public static Event observation(String id, int band, List<String> near) {
      Event e = new Event();
      e.type = "OBS";
      e.actor = id;
      e.value = band;
      e.peers = new ArrayList<>(new TreeSet<>(near));
      return e;
    }

    public static Event tag(String id, String target) {
      Event e = new Event();
      e.type = "TAG";
      e.actor = id;
      e.target = target;
      return e;
    }

    public static Event vote(String id, boolean yes) {
      Event e = new Event();
      e.type = "VOTE";
      e.actor = id;
      e.value = yes ? 1 : 0;
      return e;
    }

    public static Event ability(String id, String ability, String target) {
      Event e = new Event();
      e.type = "ABILITY";
      e.actor = id;
      e.ability = ability;
      e.target = target;
      return e;
    }

    public byte[] encode() {
      return Wire.bytes(
          o -> {
            Wire.string(o, type);
            Wire.string(o, actor);
            Wire.string(o, target);
            Wire.string(o, ability);
            o.writeInt(value);
            Wire.strings(o, peers);
          });
    }

    public static Event decode(byte[] b) throws IOException {
      DataInputStream i = Wire.input(b);
      Event e = new Event();
      e.type = Wire.string(i);
      e.actor = Wire.string(i);
      e.target = Wire.string(i);
      e.ability = Wire.string(i);
      e.value = i.readInt();
      e.peers = Wire.strings(i, 8);
      if (i.available() != 0) throw new IOException("Trailing event bytes");
      return e;
    }
  }

  public final List<Player> players;
  public final String ghost;
  public final long seed;
  public Phase phase = Phase.CALIBRATING;
  public int elapsedMs,
      penaltyMs,
      cooldownUntil,
      charges = 2,
      abilityUntil,
      abilityReadyAt,
      missingMs;
  public boolean paused;
  public String activeAbility = "", echoTarget = "", winner = "", endReason = "";
  public Vote vote;
  public final TreeSet<String> cleared = new TreeSet<>();
  public final List<Observation> observations = new ArrayList<>();
  public final List<String> timeline = new ArrayList<>();

  public Game(List<Player> players, String ghost, long seed) {
    if (players.size() < 4 || players.size() > 8)
      throw new IllegalArgumentException("A round needs 4–8 players");
    TreeSet<String> ids = new TreeSet<>();
    for (Player p : players)
      if (p.id == null
          || p.id.isBlank()
          || p.id.length() > 32
          || p.name == null
          || p.name.isBlank()
          || p.name.length() > 24
          || !ids.add(p.id)) throw new IllegalArgumentException("Invalid player");
    if (!ids.contains(ghost)) throw new IllegalArgumentException("Unknown ghost");
    this.players = List.copyOf(players);
    this.ghost = ghost;
    this.seed = seed;
    timeline.add("00:00 · Protocol opened. Calibration begins.");
  }

  public Set<String> ids() {
    TreeSet<String> ids = new TreeSet<>();
    for (Player p : players) ids.add(p.id);
    return ids;
  }

  public String name(String id) {
    for (Player p : players) if (p.id.equals(id)) return p.name;
    return "Unknown";
  }

  public int remainingMs() {
    return Math.max(0, 480000 - elapsedMs - penaltyMs);
  }

  public int interval() {
    return elapsedMs / 10000;
  }

  public Evidence evidence(String suspect) {
    Set<String> people = new HashSet<>();
    Set<Integer> intervals = new HashSet<>();
    for (Observation o : observations)
      if (elapsedMs - o.atMs <= 90000
          && o.band > 0
          && !o.observer.equals(suspect)
          && o.nearby.contains(suspect)) {
        people.add(o.observer);
        intervals.add(o.interval);
      }
    return new Evidence(people.size(), intervals.size());
  }

  public boolean recentlyNear(String a, String b) {
    for (Observation o : observations)
      if (o.observer.equals(a) && elapsedMs - o.atMs <= 15000 && o.nearby.contains(b)) return true;
    return false;
  }

  public List<Observation> history(String id) {
    List<Observation> l = new ArrayList<>();
    for (Observation o : observations) if (o.observer.equals(id)) l.add(o);
    return l.subList(Math.max(0, l.size() - 6), l.size());
  }

  public String apply(Event e) {
    if (phase == Phase.ENDED) return "This round has ended.";
    if (e.type.equals("TICK")) return tick(e);
    if (!ids().contains(e.actor)) return "Unknown player.";
    if (paused) return "Wait for all phones to reconnect.";
    if (e.type.equals("OBS")) {
      if (elapsedMs < 10000) return "Wait for the first complete signal interval.";
      if (e.value < 0
          || e.value > 2
          || e.peers.size() > 7
          || !ids().containsAll(e.peers)
          || e.peers.contains(e.actor)) return "Invalid observation.";
      for (Observation o : observations)
        if (o.observer.equals(e.actor) && o.interval == interval())
          return "An observation already exists for this interval.";
      observations.add(
          new Observation(e.actor, interval(), elapsedMs, e.value, List.copyOf(e.peers)));
      return "";
    }
    if (phase != Phase.HUNT) return "Calibration is still running.";
    switch (e.type) {
      case "TAG":
        if (vote != null) return "An accusation is already open.";
        if (elapsedMs < cooldownUntil) return "Tagging is cooling down.";
        if (!ids().contains(e.target) || e.actor.equals(e.target)) return "Choose another player.";
        if (cleared.contains(e.target)) return "This player has been cleared.";
        if (!recentlyNear(e.actor, e.target)) return "Move near this player before tagging.";
        if (!evidence(e.target).eligible()) return "More shared evidence is needed.";
        vote = new Vote();
        vote.suspect = e.target;
        vote.initiator = e.actor;
        vote.endsAt = elapsedMs + 15000;
        vote.ballots.put(e.actor, true);
        log(name(e.actor) + " nominated " + name(e.target) + ".");
        return "";
      case "VOTE":
        if (vote == null) return "There is no accusation to vote on.";
        if (e.actor.equals(vote.suspect)) return "The suspect cannot vote.";
        if (vote.ballots.containsKey(e.actor)) return "Your vote is locked.";
        if (e.value != 0 && e.value != 1) return "Invalid ballot.";
        vote.ballots.put(e.actor, e.value == 1);
        resolveVote(false);
        return "";
      case "ABILITY":
        if (!e.actor.equals(ghost)) return "Only the ghost has abilities.";
        if (charges == 0) return "No charges remain.";
        if (elapsedMs < abilityReadyAt) return "Your abilities are cooling down.";
        if (!e.ability.equals("ECHO") && !e.ability.equals("FADE")) return "Unknown ability.";
        if (e.ability.equals("ECHO")
            && (!ids().contains(e.target)
                || e.target.equals(ghost)
                || !recentlyNear(ghost, e.target))) return "Echo needs a recent nearby player.";
        charges--;
        activeAbility = e.ability;
        echoTarget = e.target;
        abilityUntil = elapsedMs + 20000;
        abilityReadyAt = elapsedMs + 30000;
        // Ability details appear only on the results screen.
        log(
            "Ghost used "
                + e.ability
                + (e.ability.equals("ECHO") ? " on " + name(e.target) : "")
                + ".");
        return "";
      default:
        return "Unknown action.";
    }
  }

  private String tick(Event e) {
    if (e.value < 0 || e.value > 5000 || !ids().containsAll(e.peers))
      return "Invalid clock update.";
    if (!new HashSet<>(e.peers).containsAll(ids())) {
      missingMs += e.value;
      paused = missingMs >= 10000;
      if (missingMs >= 70000)
        end("NONE", "A phone was absent for 60 seconds after the reconnect pause.");
      return "";
    }
    missingMs = 0;
    paused = false;
    elapsedMs += e.value;
    if (remainingMs() == 0) {
      end("GHOST", "The ghost escaped before the room could agree.");
      return "";
    }
    if (phase == Phase.CALIBRATING && elapsedMs >= 30000) {
      phase = Phase.HUNT;
      log("Calibration complete. The hunt begins.");
    }
    if (abilityUntil <= elapsedMs) {
      activeAbility = "";
      echoTarget = "";
    }
    observations.removeIf(o -> elapsedMs - o.atMs > 90000);
    if (vote != null) resolveVote(elapsedMs >= vote.endsAt);
    return "";
  }

  private void resolveVote(boolean expired) {
    int yes = 0;
    for (boolean b : vote.ballots.values()) if (b) yes++;
    int required = (players.size() - 1) / 2 + 1;
    if (yes >= required) {
      String suspect = vote.suspect;
      vote = null;
      if (suspect.equals(ghost)) {
        end("HUNTERS", name(ghost) + " was identified by the room.");
      } else {
        cleared.add(suspect);
        penaltyMs += 45000;
        charges = Math.min(3, charges + 1);
        cooldownUntil = elapsedMs + 30000;
        log(name(suspect) + " is clear. 45 seconds lost; ghost gained a charge.");
        if (remainingMs() == 0) end("GHOST", "A wrong accusation let the ghost escape.");
      }
    } else if (expired || vote.ballots.size() == players.size() - 1) {
      vote = null;
      cooldownUntil = elapsedMs + 30000;
      log("Accusation failed. No role revealed.");
    }
  }

  private void end(String who, String reason) {
    phase = Phase.ENDED;
    winner = who;
    endReason = reason;
    vote = null;
    log(reason);
  }

  public void abort(String reason) {
    if (phase != Phase.ENDED) end("NONE", reason);
  }

  private void log(String text) {
    timeline.add(
        String.format(
            Locale.ROOT, "%02d:%02d · %s", elapsedMs / 60000, (elapsedMs / 1000) % 60, text));
    if (timeline.size() > 256) timeline.remove(0);
  }

  public byte[] encode() {
    return Wire.bytes(
        o -> {
          o.writeInt(1);
          o.writeInt(players.size());
          for (Player p : players) {
            Wire.string(o, p.id);
            Wire.string(o, p.name);
          }
          Wire.string(o, ghost);
          o.writeLong(seed);
          o.writeInt(phase.ordinal());
          o.writeInt(elapsedMs);
          o.writeInt(penaltyMs);
          o.writeInt(cooldownUntil);
          o.writeInt(charges);
          o.writeInt(abilityUntil);
          o.writeInt(abilityReadyAt);
          o.writeInt(missingMs);
          o.writeBoolean(paused);
          Wire.string(o, activeAbility);
          Wire.string(o, echoTarget);
          Wire.string(o, winner);
          Wire.string(o, endReason);
          Wire.strings(o, cleared);
          o.writeBoolean(vote != null);
          if (vote != null) {
            Wire.string(o, vote.suspect);
            Wire.string(o, vote.initiator);
            o.writeInt(vote.endsAt);
            o.writeInt(vote.ballots.size());
            for (var b : vote.ballots.entrySet()) {
              Wire.string(o, b.getKey());
              o.writeBoolean(b.getValue());
            }
          }
          o.writeInt(observations.size());
          for (Observation a : observations) {
            Wire.string(o, a.observer);
            o.writeInt(a.interval);
            o.writeInt(a.atMs);
            o.writeInt(a.band);
            Wire.strings(o, a.nearby);
          }
          Wire.strings(o, timeline);
        });
  }

  public static Game decode(byte[] data) throws IOException {
    DataInputStream i = Wire.input(data);
    if (i.readInt() != 1) throw new IOException("Unsupported state version");
    int n = Wire.count(i, 8);
    List<Player> ps = new ArrayList<>();
    for (int j = 0; j < n; j++) ps.add(new Player(Wire.string(i), Wire.string(i)));
    Game g;
    try {
      g = new Game(ps, Wire.string(i), i.readLong());
    } catch (IllegalArgumentException e) {
      throw new IOException(e);
    }
    int p = i.readInt();
    if (p < 0 || p >= Phase.values().length) throw new IOException("Invalid phase");
    g.phase = Phase.values()[p];
    g.elapsedMs = i.readInt();
    g.penaltyMs = i.readInt();
    g.cooldownUntil = i.readInt();
    g.charges = i.readInt();
    g.abilityUntil = i.readInt();
    g.abilityReadyAt = i.readInt();
    g.missingMs = i.readInt();
    g.paused = i.readBoolean();
    g.activeAbility = Wire.string(i);
    g.echoTarget = Wire.string(i);
    g.winner = Wire.string(i);
    g.endReason = Wire.string(i);
    g.cleared.addAll(Wire.strings(i, 8));
    if (i.readBoolean()) {
      g.vote = new Vote();
      g.vote.suspect = Wire.string(i);
      g.vote.initiator = Wire.string(i);
      g.vote.endsAt = i.readInt();
      int c = Wire.count(i, 8);
      for (int j = 0; j < c; j++) g.vote.ballots.put(Wire.string(i), i.readBoolean());
    }
    n = Wire.count(i, 100);
    for (int j = 0; j < n; j++)
      g.observations.add(
          new Observation(
              Wire.string(i), i.readInt(), i.readInt(), i.readInt(), Wire.strings(i, 8)));
    g.timeline.clear();
    g.timeline.addAll(Wire.strings(i, 256));
    if (i.available() != 0 || g.elapsedMs < 0 || g.charges < 0 || g.charges > 3)
      throw new IOException("Invalid state");
    return g;
  }

  public Game copy() {
    try {
      return decode(encode());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
