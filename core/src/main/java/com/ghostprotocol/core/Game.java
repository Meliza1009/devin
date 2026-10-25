package com.ghostprotocol.core;

import java.io.*;
import java.util.*;

/** Deterministic, replicated Haunted Heist rules. No radio, UI, or wall clock dependencies. */
public final class Game {
  public static final int VERSION = 2,
      ROUND_MS = 600000,
      TASK_MS = 30000,
      VISIT_MS = 15000,
      SEAL_MS = 6000,
      MAX_ALARM = 100;
  public static final String[] GLYPHS = {"MOON", "KEY", "EYE", "CROWN"};

  public enum Phase {
    HUNT,
    SHOWDOWN,
    ENDED
  }

  public enum Station {
    NONE,
    VAULT,
    WORKSHOP,
    EXIT
  }

  public enum Specialty {
    ANALYST,
    ENGINEER,
    COURIER,
    WARDEN
  }

  public enum RelicState {
    VAULT,
    RAW,
    SEALED,
    DELIVERED
  }

  public enum Visibility {
    PUBLIC,
    GHOST,
    DEBRIEF
  }

  public record Player(String id, String name) {}

  public record Observation(
      String observer, int interval, int atMs, int band, List<String> nearby) {}

  public record Evidence(int observers, int intervals) {
    public boolean eligible() {
      return observers >= 2 && intervals >= 3;
    }
  }

  public record Entry(
      int atMs, String code, String actor, String target, String detail, Visibility visibility) {}

  public static final class Agent {
    public Specialty specialty;
    public Station station = Station.NONE;
    public int visitedAt = -1, perkReadyAt, shieldUntil, hauntedUntil, immuneUntil;

    Agent(Specialty specialty) {
      this.specialty = specialty;
    }
  }

  public static final class Relic {
    public final String id, name;
    public RelicState state = RelicState.VAULT;
    public Station location = Station.VAULT;
    public String owner = "";
    public int heldAt, protectedUntil;

    Relic(String id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public static final class Task {
    public String id, kind, operator, reader = "", relic = "";
    public Station station;
    public int createdAt, endsAt, code, hints;

    public int answerCode() {
      return code;
    }

    public boolean active() {
      return !reader.isEmpty();
    }

    public List<String> instructions() {
      return List.of(GLYPHS[(code >> 4) & 3], GLYPHS[(code >> 2) & 3], GLYPHS[code & 3]);
    }
  }

  public record Seal(String relic, int finishesAt) {}

  public record Offer(String relic, String from, String to, int expiresAt) {}

  public static final class Vote {
    public String suspect, initiator;
    public int endsAt;
    public final TreeMap<String, Boolean> ballots = new TreeMap<>();
  }

  public static final class Event {
    public String type, actor = "", target = "", ability = "", requestId = "";
    public int value;
    public List<String> peers = List.of();

    private static Event make(String type, String actor, String target, String option, int value) {
      Event e = new Event();
      e.type = type;
      e.actor = actor;
      e.target = target;
      e.ability = option;
      e.value = value;
      if (!type.equals("TICK") && !type.equals("OBS")) e.requestId = RoomCrypto.newId();
      return e;
    }

    public static Event tick(int ms, Collection<String> online) {
      Event e = make("TICK", "", "", "", ms);
      e.peers = new ArrayList<>(new TreeSet<>(online));
      return e;
    }

    public static Event observation(String actor, int band, List<String> near) {
      Event e = make("OBS", actor, "", "", band);
      e.peers = new ArrayList<>(new TreeSet<>(near));
      return e;
    }

    public static Event scan(String actor, Station station) {
      return make("SCAN", actor, station.name(), "", 0);
    }

    public static Event task(String actor, String kind, String relic) {
      return make("TASK", actor, relic, kind, 0);
    }

    public static Event join(String actor, String task) {
      return make("JOIN", actor, task, "", 0);
    }

    public static Event answer(String actor, String task, int code) {
      return make("ANSWER", actor, task, "", code);
    }

    public static Event cancel(String actor) {
      return make("CANCEL", actor, "", "", 0);
    }

    public static Event seal(String actor, String relic) {
      return make("SEAL", actor, relic, "", 0);
    }

    public static Event deposit(String actor, String relic) {
      return make("DEPOSIT", actor, relic, "", 0);
    }

    public static Event take(String actor, String relic) {
      return make("TAKE", actor, relic, "", 0);
    }

    public static Event offer(String actor, String relic, String to) {
      return make("OFFER", actor, relic, to, 0);
    }

    public static Event accept(String actor, String relic) {
      return make("ACCEPT", actor, relic, "", 0);
    }

    public static Event decline(String actor, String relic) {
      return make("DECLINE", actor, relic, "", 0);
    }

    public static Event tag(String actor, String suspect) {
      return make("TAG", actor, suspect, "", 0);
    }

    public static Event vote(String actor, boolean yes) {
      return make("VOTE", actor, "", "", yes ? 1 : 0);
    }

    public static Event ability(String actor, String ability, String target) {
      return make("ABILITY", actor, target, ability, 0);
    }

    public static Event perk(String actor, String target) {
      return make("PERK", actor, target, "", 0);
    }

    public byte[] encode() {
      return Wire.bytes(
          o -> {
            o.writeInt(VERSION);
            Wire.string(o, type);
            Wire.string(o, actor);
            Wire.string(o, target);
            Wire.string(o, ability);
            Wire.string(o, requestId);
            o.writeInt(value);
            Wire.strings(o, peers);
          });
    }

    public static Event decode(byte[] bytes) throws IOException {
      DataInputStream i = Wire.input(bytes);
      if (i.readInt() != VERSION) throw new IOException("Update all phones to Haunted Heist 2.0");
      Event e = new Event();
      e.type = Wire.string(i);
      e.actor = Wire.string(i);
      e.target = Wire.string(i);
      e.ability = Wire.string(i);
      e.requestId = Wire.string(i);
      e.value = i.readInt();
      e.peers = Wire.strings(i, 8);
      if (i.available() != 0) throw new IOException("Trailing event bytes");
      return e;
    }
  }

  public final List<Player> players;
  public final String ghost;
  public final long seed;
  public Phase phase = Phase.HUNT;
  public int elapsedMs,
      alarm,
      charges = 2,
      abilityReadyAt,
      accusationsLeft = 2,
      missingMs,
      nextTask = 1,
      regenAt = 30000,
      purgeReadyAt,
      nextEvent = 120000,
      eventUntil;
  public boolean exposed, paused;
  public String winner = "", endReason = "", roomEvent = "";
  public Station lockedStation = Station.NONE;
  public Vote vote;
  public final TreeSet<String> cleared = new TreeSet<>();
  public final TreeMap<String, Agent> agents = new TreeMap<>();
  public final TreeMap<String, Relic> relics = new TreeMap<>();
  public final TreeMap<String, Task> tasks = new TreeMap<>();
  public final TreeMap<String, Seal> seals = new TreeMap<>();
  public final TreeMap<String, Offer> offers = new TreeMap<>();
  public final EnumMap<Station, Integer> jamUntil = new EnumMap<>(Station.class),
      jamReadyAt = new EnumMap<>(Station.class);
  public final List<Observation> observations = new ArrayList<>();
  public final List<Entry> timeline = new ArrayList<>();
  private final LinkedHashSet<String> applied = new LinkedHashSet<>();

  public Game(List<Player> players, String ghost, long seed) {
    if (players.size() < 4 || players.size() > 8)
      throw new IllegalArgumentException("A heist needs 4–8 players");
    Set<String> ids = new HashSet<>();
    for (Player p : players)
      if (p.id() == null
          || p.id().isBlank()
          || p.id().length() > 32
          || p.name() == null
          || p.name().isBlank()
          || p.name().length() > 24
          || !ids.add(p.id())) throw new IllegalArgumentException("Invalid player");
    if (!ids.contains(ghost)) throw new IllegalArgumentException("Unknown ghost");
    this.players = List.copyOf(players);
    this.ghost = ghost;
    this.seed = seed;
    int offset = Math.floorMod((int) seed, 4), n = 0;
    for (Player p : players) agents.put(p.id(), new Agent(Specialty.values()[(offset + n++) % 4]));
    relics.put("crown", new Relic("crown", "Hollow Crown"));
    relics.put("lens", new Relic("lens", "Echo Lens"));
    relics.put("key", new Relic("key", "Night Key"));
    log("START", "", "", "The heist is live. Recover, seal, escape.", Visibility.PUBLIC);
  }

  public Set<String> ids() {
    return new TreeSet<>(agents.keySet());
  }

  public String name(String id) {
    for (Player p : players) if (p.id().equals(id)) return p.name();
    return "Unknown";
  }

  public int remainingMs() {
    return Math.max(0, ROUND_MS - elapsedMs);
  }

  public int interval() {
    return elapsedMs / 10000;
  }

  public int capacity(String id) {
    return agents.get(id).specialty == Specialty.COURIER ? 2 : 1;
  }

  public List<Relic> inventory(String id) {
    List<Relic> r = new ArrayList<>();
    for (Relic item : relics.values()) if (item.owner.equals(id)) r.add(item);
    return r;
  }

  public int delivered() {
    int n = 0;
    for (Relic r : relics.values()) if (r.state == RelicState.DELIVERED) n++;
    return n;
  }

  public Task taskFor(String id) {
    for (Task t : tasks.values()) if (t.operator.equals(id) || t.reader.equals(id)) return t;
    return null;
  }

  public boolean visited(String id, Station station) {
    Agent a = agents.get(id);
    return a != null
        && a.station == station
        && a.visitedAt >= 0
        && elapsedMs - a.visitedAt <= VISIT_MS;
  }

  public boolean stationOpen(Station station) {
    return jamUntil.getOrDefault(station, 0) <= elapsedMs
        && !(eventUntil > elapsedMs && roomEvent.equals("LOCKDOWN") && lockedStation == station);
  }

  public boolean recentlyNear(String a, String b) {
    for (Observation o : observations)
      if (o.observer().equals(a) && elapsedMs - o.atMs() <= VISIT_MS && o.nearby().contains(b))
        return true;
    return false;
  }

  public List<Observation> history(String id) {
    List<Observation> r = new ArrayList<>();
    for (Observation o : observations) if (o.observer().equals(id)) r.add(o);
    return List.copyOf(r.subList(Math.max(0, r.size() - 6), r.size()));
  }

  public Evidence evidence(String suspect) {
    Set<String> witnesses = new HashSet<>();
    Set<Integer> scans = new HashSet<>();
    for (Observation o : observations)
      if (o.band() > 0 && !o.observer().equals(suspect) && o.nearby().contains(suspect)) {
        witnesses.add(o.observer());
        scans.add(o.interval());
      }
    return new Evidence(witnesses.size(), scans.size());
  }

  public List<Entry> visibleEvents(String viewer, boolean debrief) {
    List<Entry> r = new ArrayList<>();
    for (Entry e : timeline)
      if (debrief
          || e.visibility() == Visibility.PUBLIC
          || (e.visibility() == Visibility.GHOST && viewer.equals(ghost))) r.add(e);
    return List.copyOf(r);
  }

  public boolean hasApplied(String requestId) {
    return applied.contains(requestId);
  }

  private String crewReady(String actor) {
    if (exposed && actor.equals(ghost))
      return "You are exposed. Use your ghost controls to stop extraction.";
    if (agents.get(actor).hauntedUntil > elapsedMs)
      return "Haunted: relic actions resume in "
          + ((agents.get(actor).hauntedUntil - elapsedMs + 999) / 1000)
          + "s.";
    return "";
  }

  private String atStation(String actor, Station station, boolean allowJam) {
    String e = crewReady(actor);
    if (!e.isEmpty()) return e;
    if (!visited(actor, station))
      return "Scan the " + station.name().toLowerCase(Locale.ROOT) + " checkpoint first.";
    if (!allowJam && !stationOpen(station))
      return "This checkpoint is disabled. Repair it or wait for it to reopen.";
    return "";
  }

  public String apply(Event e) {
    if (!e.requestId.isEmpty() && applied.contains(e.requestId)) return "";
    if (phase == Phase.ENDED) return "This heist has ended.";
    if (e.type.equals("TICK")) return tick(e);
    if (!agents.containsKey(e.actor)) return "Unknown player.";
    if (paused) return "Wait for every phone to reconnect.";
    if (e.type.equals("OBS")) {
      if (elapsedMs < 10000) return "Wait for the first completed scan.";
      if (e.value < 0
          || e.value > 2
          || e.peers.size() > 7
          || !ids().containsAll(e.peers)
          || e.peers.contains(e.actor)) return "Invalid observation.";
      for (Observation o : observations)
        if (o.observer().equals(e.actor) && o.interval() == interval())
          return "A reading already exists for this interval.";
      observations.add(
          new Observation(e.actor, interval(), elapsedMs, e.value, List.copyOf(e.peers)));
      return "";
    }
    if (!e.requestId.matches("[0-9a-f]{16}")) return "Invalid action identifier.";
    String error = action(e);
    if (error.isEmpty()) {
      applied.add(e.requestId);
      while (applied.size() > 512) applied.remove(applied.iterator().next());
      checkEnd();
    }
    return error;
  }

  private String action(Event e) {
    Agent a = agents.get(e.actor);
    Relic r = relics.get(e.target);
    String error;
    switch (e.type) {
      case "SCAN":
        Station station;
        try {
          station = Station.valueOf(e.target);
        } catch (IllegalArgumentException x) {
          return "Unknown checkpoint.";
        }
        if (station == Station.NONE) return "Scan a valid checkpoint.";
        if (a.station != station) {
          cancelTask(e.actor);
          seals.remove(e.actor);
        }
        a.station = station;
        a.visitedAt = elapsedMs;
        return "";
      case "TASK":
        return startTask(e);
      case "JOIN":
        return joinTask(e);
      case "ANSWER":
        return answerTask(e);
      case "CANCEL":
        cancelTask(e.actor);
        seals.remove(e.actor);
        return "";
      case "SEAL":
        error = atStation(e.actor, Station.WORKSHOP, false);
        if (!error.isEmpty()) return error;
        if (taskFor(e.actor) != null || seals.containsKey(e.actor))
          return "Finish or cancel your current action first.";
        if (r == null || !r.owner.equals(e.actor) || r.state != RelicState.RAW)
          return "Carry an unsealed relic to the Workshop.";
        seals.put(e.actor, new Seal(r.id, elapsedMs + SEAL_MS));
        return "";
      case "DEPOSIT":
        error = atStation(e.actor, Station.EXIT, false);
        if (!error.isEmpty()) return error;
        if (r == null || !r.owner.equals(e.actor) || r.state != RelicState.SEALED)
          return "Only a sealed relic can be deposited.";
        releaseRelic(r);
        r.state = RelicState.DELIVERED;
        r.location = Station.EXIT;
        r.owner = "";
        log(
            "DEPOSIT",
            e.actor,
            r.id,
            name(e.actor) + " delivered the " + r.name + ". " + delivered() + "/3 secured.",
            Visibility.PUBLIC);
        return "";
      case "TAKE":
        error = atStation(e.actor, Station.WORKSHOP, false);
        if (!error.isEmpty()) return error;
        if (r == null
            || !r.owner.isEmpty()
            || r.location != Station.WORKSHOP
            || r.state == RelicState.DELIVERED) return "This relic is not in Workshop storage.";
        if (inventory(e.actor).size() >= capacity(e.actor)) return "Your inventory is full.";
        r.owner = e.actor;
        r.heldAt = elapsedMs;
        log(
            "TAKE",
            e.actor,
            r.id,
            name(e.actor) + " collected the " + r.name + ".",
            Visibility.PUBLIC);
        return "";
      case "OFFER":
        error = crewReady(e.actor);
        if (!error.isEmpty()) return error;
        if (r == null
            || !r.owner.equals(e.actor)
            || !agents.containsKey(e.ability)
            || e.actor.equals(e.ability)) return "Choose a relic you carry and another player.";
        if (!crewReady(e.ability).isEmpty()) return "That player cannot receive a relic right now.";
        if (!recentlyNear(e.actor, e.ability))
          return "Walk near this teammate before offering a handoff.";
        if (inventory(e.ability).size() >= capacity(e.ability)) return "Their inventory is full.";
        offers.put(r.id, new Offer(r.id, e.actor, e.ability, elapsedMs + 15000));
        return "";
      case "ACCEPT":
        Offer offer = offers.get(e.target);
        error = crewReady(e.actor);
        if (!error.isEmpty()) return error;
        if (offer == null
            || !offer.to().equals(e.actor)
            || offer.expiresAt() <= elapsedMs
            || r == null
            || !r.owner.equals(offer.from())) return "That handoff is no longer available.";
        if (!recentlyNear(offer.from(), e.actor) && !recentlyNear(e.actor, offer.from()))
          return "Move back near the sender.";
        if (inventory(e.actor).size() >= capacity(e.actor)) return "Your inventory is full.";
        releaseRelic(r);
        r.owner = e.actor;
        r.heldAt = elapsedMs;
        log(
            "HANDOFF",
            offer.from(),
            e.actor,
            name(offer.from()) + " handed " + r.name + " to " + name(e.actor) + ".",
            Visibility.PUBLIC);
        return "";
      case "DECLINE":
        Offer declined = offers.get(e.target);
        if (declined == null
            || (!declined.to().equals(e.actor) && !declined.from().equals(e.actor)))
          return "No handoff to cancel.";
        offers.remove(e.target);
        return "";
      case "TAG":
        if (exposed) return "The ghost is already exposed. Finish the heist.";
        if (elapsedMs < 90000)
          return "Accusations unlock after 90 seconds. Start recovering relics.";
        if (vote != null) return "An accusation is already open.";
        if (accusationsLeft == 0) return "Both accusation opportunities have been spent.";
        if (!agents.containsKey(e.target) || e.actor.equals(e.target) || cleared.contains(e.target))
          return "Choose another uncleared player.";
        accusationsLeft--;
        vote = new Vote();
        vote.suspect = e.target;
        vote.initiator = e.actor;
        vote.endsAt = elapsedMs + 20000;
        vote.ballots.put(e.actor, true);
        log(
            "ACCUSE",
            e.actor,
            e.target,
            name(e.actor) + " accused " + name(e.target) + ". The crew is voting.",
            Visibility.PUBLIC);
        return "";
      case "VOTE":
        if (vote == null || vote.suspect.equals(e.actor) || vote.ballots.containsKey(e.actor))
          return "You cannot cast another ballot in this vote.";
        if (e.value != 0 && e.value != 1) return "Invalid ballot.";
        vote.ballots.put(e.actor, e.value == 1);
        resolveVote(false);
        return "";
      case "ABILITY":
        return sabotage(e);
      case "PERK":
        return perk(e);
      default:
        return "Unknown action.";
    }
  }

  private String startTask(Event e) {
    Station station;
    switch (e.ability) {
      case "BREACH":
      case "RECALL":
        station = Station.VAULT;
        break;
      case "PURGE":
        station = Station.WORKSHOP;
        break;
      case "EXTRACT":
        station = Station.EXIT;
        break;
      case "REPAIR":
        station = agents.get(e.actor).station;
        break;
      default:
        return "Unknown task.";
    }
    String error = atStation(e.actor, station, e.ability.equals("REPAIR"));
    if (!error.isEmpty()) return error;
    if (taskFor(e.actor) != null || seals.containsKey(e.actor))
      return "Finish or cancel your current action first.";
    for (Task t : tasks.values())
      if (t.station == station) return "Another pair is using this checkpoint. Join them or wait.";
    Relic relic = null;
    if (e.ability.equals("BREACH")) {
      if (inventory(e.actor).size() >= capacity(e.actor))
        return "Hand off or deposit a relic before breaching another.";
      for (Relic r : relics.values())
        if (r.state == RelicState.VAULT) {
          relic = r;
          break;
        }
      if (relic == null) return "All relics have left the Vault.";
    }
    if (e.ability.equals("RECALL")) {
      relic = relics.get(e.target);
      if (relic == null || relic.owner.isEmpty() || elapsedMs - relic.heldAt < 60000)
        return "A carried relic can be recalled after 60 seconds.";
    }
    if (e.ability.equals("EXTRACT") && delivered() != 3)
      return "Deliver all three sealed relics before opening the Exit.";
    if (e.ability.equals("PURGE") && alarm == 0) return "Alarm is already at zero.";
    if (e.ability.equals("PURGE") && purgeReadyAt > elapsedMs)
      return "Purge recharges in " + ((purgeReadyAt - elapsedMs + 999) / 1000) + " seconds.";
    if (e.ability.equals("REPAIR") && jamUntil.getOrDefault(station, 0) <= elapsedMs)
      return "This station has no ghost jam to repair.";
    Task task = new Task();
    task.id = "task-" + nextTask++;
    task.kind = e.ability;
    task.station = station;
    task.operator = e.actor;
    task.createdAt = elapsedMs;
    task.endsAt = elapsedMs + TASK_MS;
    task.relic = relic == null ? "" : relic.id;
    task.code = Math.floorMod(Objects.hash(seed, task.id, task.kind), 64);
    tasks.put(task.id, task);
    return "";
  }

  private String joinTask(Event e) {
    Task t = tasks.get(e.target);
    if (t == null || t.active()) return "This task is no longer waiting for a partner.";
    String error = atStation(e.actor, t.station, t.kind.equals("REPAIR"));
    if (!error.isEmpty()) return error;
    if (t.operator.equals(e.actor) || taskFor(e.actor) != null || seals.containsKey(e.actor))
      return "A different available teammate must join.";
    if (!visited(t.operator, t.station)) return "Your partner must scan this checkpoint again.";
    if (!recentlyNear(e.actor, t.operator) && !recentlyNear(t.operator, e.actor))
      return "Stand near your partner until the next Bluetooth reading arrives.";
    t.reader = e.actor;
    t.endsAt =
        elapsedMs + (roomEvent.equals("OVERCLOCK") && eventUntil > elapsedMs ? 45000 : TASK_MS);
    t.hints = roomEvent.equals("OVERCLOCK") && eventUntil > elapsedMs ? 1 : 0;
    return "";
  }

  private String answerTask(Event e) {
    Task t = tasks.get(e.target);
    if (t == null || !t.active() || !t.operator.equals(e.actor))
      return "Only the operator can submit this active puzzle.";
    if (!crewReady(t.operator).isEmpty() || !crewReady(t.reader).isEmpty())
      return "A partner is haunted. Wait for the effect to end.";
    if (e.value < 0 || e.value > 63) return "Enter three valid symbols.";
    if (e.value != t.code) {
      tasks.remove(t.id);
      changeAlarm(5);
      log(
          "PUZZLE_FAIL",
          e.actor,
          t.station.name(),
          "Incorrect sequence at the " + t.station.name().toLowerCase(Locale.ROOT) + ". Alarm +5.",
          Visibility.PUBLIC);
      return "";
    }
    Relic r = relics.get(t.relic);
    switch (t.kind) {
      case "BREACH":
        if (r == null
            || r.state != RelicState.VAULT
            || inventory(e.actor).size() >= capacity(e.actor))
          return "Your inventory changed. Cancel and try again.";
        r.state = RelicState.RAW;
        r.owner = e.actor;
        r.location = Station.NONE;
        r.heldAt = elapsedMs;
        log(
            "RECOVER",
            e.actor,
            r.id,
            name(e.actor) + " recovered the " + r.name + ".",
            Visibility.PUBLIC);
        break;
      case "RECALL":
        if (r == null || r.owner.isEmpty() || elapsedMs - r.heldAt < 60000)
          return "This relic no longer needs a recall.";
        releaseRelic(r);
        r.owner = "";
        r.state = RelicState.VAULT;
        r.location = Station.VAULT;
        changeAlarm(10);
        log(
            "RECALL",
            e.actor,
            r.id,
            r.name + " recalled to the Vault. Alarm +10.",
            Visibility.PUBLIC);
        break;
      case "REPAIR":
        jamUntil.put(t.station, elapsedMs);
        log(
            "REPAIR",
            e.actor,
            t.station.name(),
            "The " + t.station.name().toLowerCase(Locale.ROOT) + " jam was cleared.",
            Visibility.PUBLIC);
        break;
      case "PURGE":
        changeAlarm(-15);
        purgeReadyAt = elapsedMs + 60000;
        log("PURGE", e.actor, "", "Workshop purge complete. Alarm −15.", Visibility.PUBLIC);
        break;
      case "EXTRACT":
        if (delivered() != 3) return "All three relics must be secured.";
        end("HUNTERS", "Three relics secured. The crew escaped the haunted facility.");
        break;
      default:
        return "Unknown task.";
    }
    tasks.remove(t.id);
    return "";
  }

  private String sabotage(Event e) {
    if (!e.actor.equals(ghost)) return "Only the ghost can sabotage.";
    if (elapsedMs < abilityReadyAt)
      return "Ghost tools recharge in " + ((abilityReadyAt - elapsedMs + 999) / 1000) + " seconds.";
    int cost = e.ability.equals("CORRUPT") ? 2 : 1;
    if (charges < cost) return "Not enough ghost energy.";
    switch (e.ability) {
      case "JAM":
        Station s;
        try {
          s = Station.valueOf(e.target);
        } catch (IllegalArgumentException x) {
          return "Choose a checkpoint.";
        }
        if (s == Station.NONE || (!exposed && !visited(ghost, s)))
          return "Visit and scan that checkpoint before secretly jamming it.";
        if (jamReadyAt.getOrDefault(s, 0) > elapsedMs)
          return "This checkpoint is protected from another jam for "
              + ((jamReadyAt.get(s) - elapsedMs + 999) / 1000)
              + " seconds.";
        jamUntil.put(s, elapsedMs + 25000);
        jamReadyAt.put(s, elapsedMs + 70000);
        log(
            "JAM",
            "",
            s.name(),
            "Ghost interference disabled the "
                + s.name().toLowerCase(Locale.ROOT)
                + " for 25 seconds.",
            Visibility.PUBLIC);
        break;
      case "CORRUPT":
        if (exposed) return "Use Haunt during the showdown.";
        Relic relic = relics.get(e.target);
        if (relic == null
            || relic.owner.isEmpty()
            || relic.owner.equals(ghost)
            || relic.state != RelicState.SEALED) return "Choose a nearby carrier's sealed relic.";
        if (!recentlyNear(ghost, relic.owner))
          return "Approach the carrier and wait for a nearby reading.";
        if (relic.protectedUntil > elapsedMs || agents.get(relic.owner).shieldUntil > elapsedMs)
          return "That relic is protected by a ward.";
        relic.state = RelicState.RAW;
        log(
            "CORRUPT",
            "",
            relic.id,
            relic.name + " was corrupted. Return it to the Workshop.",
            Visibility.PUBLIC);
        break;
      case "HAUNT":
        if (!exposed) return "Haunt unlocks after the ghost is exposed.";
        Agent target = agents.get(e.target);
        if (target == null || e.target.equals(ghost) || !recentlyNear(ghost, e.target))
          return "Approach a hunter before haunting them.";
        if (target.immuneUntil > elapsedMs || target.shieldUntil > elapsedMs)
          return "That hunter is protected from haunting.";
        target.hauntedUntil = elapsedMs + 8000;
        target.immuneUntil = elapsedMs + 38000;
        seals.remove(e.target);
        log(
            "HAUNT",
            ghost,
            e.target,
            name(e.target) + " is haunted for 8 seconds.",
            Visibility.PUBLIC);
        break;
      default:
        return "Unknown ghost tool.";
    }
    charges -= cost;
    abilityReadyAt = elapsedMs + 20000;
    changeAlarm(5);
    log(
        "SABOTAGE",
        ghost,
        e.target,
        e.ability + " used. " + charges + " energy remaining.",
        Visibility.GHOST);
    return "";
  }

  private String perk(Event e) {
    Agent a = agents.get(e.actor);
    String error = crewReady(e.actor);
    if (!error.isEmpty()) return error;
    if (a.perkReadyAt > elapsedMs) return "Your specialty is recharging.";
    switch (a.specialty) {
      case ANALYST:
        Task t = taskFor(e.actor);
        if (t == null || !t.active() || t.hints >= 3)
          return "Join an active puzzle to reveal a symbol.";
        t.hints++;
        a.perkReadyAt = elapsedMs + 60000;
        break;
      case ENGINEER:
        if (!visited(e.actor, a.station) || jamUntil.getOrDefault(a.station, 0) <= elapsedMs)
          return "Scan a jammed checkpoint to bypass it.";
        jamUntil.put(a.station, elapsedMs);
        a.perkReadyAt = elapsedMs + 90000;
        log(
            "BYPASS",
            e.actor,
            a.station.name(),
            name(e.actor) + " bypassed a checkpoint jam.",
            Visibility.PUBLIC);
        break;
      case WARDEN:
        Agent target = agents.get(e.target);
        if (target == null || (!e.actor.equals(e.target) && !recentlyNear(e.actor, e.target)))
          return "Choose yourself or a nearby carrier to protect.";
        target.shieldUntil = elapsedMs + 30000;
        a.perkReadyAt = elapsedMs + 60000;
        log(
            "WARD",
            e.actor,
            e.target,
            name(e.target) + " received a 30-second ward.",
            Visibility.PUBLIC);
        break;
      case COURIER:
        return "Your second inventory slot is always available.";
    }
    return "";
  }

  private void cancelTask(String actor) {
    Task t = taskFor(actor);
    if (t != null) tasks.remove(t.id);
  }

  private void releaseRelic(Relic r) {
    offers.remove(r.id);
    seals.entrySet().removeIf(e -> e.getValue().relic().equals(r.id));
  }

  private void changeAlarm(int amount) {
    alarm = Math.max(0, Math.min(MAX_ALARM, alarm + amount));
  }

  private void resolveVote(boolean expired) {
    int yes = 0;
    for (boolean b : vote.ballots.values()) if (b) yes++;
    if (yes >= (players.size() - 1) / 2 + 1) {
      String suspect = vote.suspect;
      vote = null;
      if (suspect.equals(ghost)) {
        exposed = true;
        phase = Phase.SHOWDOWN;
        regenAt = elapsedMs + 20000;
        cancelTask(ghost);
        seals.remove(ghost);
        for (Relic r : inventory(ghost)) {
          releaseRelic(r);
          r.owner = "";
          r.location = Station.WORKSHOP;
        }
        log(
            "EXPOSE",
            ghost,
            "",
            name(ghost) + " is the ghost. Showdown begins—finish the escape!",
            Visibility.PUBLIC);
      } else {
        cleared.add(suspect);
        changeAlarm(20);
        log("WRONG", suspect, "", name(suspect) + " is innocent. Alarm +20.", Visibility.PUBLIC);
      }
    } else if (expired || vote.ballots.size() == players.size() - 1) {
      vote = null;
      log("VOTE_FAIL", "", "", "The accusation failed. No role was revealed.", Visibility.PUBLIC);
    }
  }

  private String tick(Event e) {
    if (e.value < 0 || e.value > 5000 || !ids().containsAll(e.peers))
      return "Invalid clock update.";
    if (!new HashSet<>(e.peers).containsAll(ids())) {
      missingMs += e.value;
      paused = missingMs >= 10000;
      if (missingMs >= 70000) abort("A phone did not return. This heist has no winner.");
      return "";
    }
    missingMs = 0;
    paused = false;
    elapsedMs += e.value;
    if (elapsedMs >= ROUND_MS) {
      checkEnd();
      return "";
    }
    while (elapsedMs >= regenAt) {
      charges = Math.min(3, charges + 1);
      regenAt += exposed ? 20000 : 30000;
    }
    for (var item : new ArrayList<>(seals.entrySet()))
      if (item.getValue().finishesAt() <= elapsedMs) {
        Relic r = relics.get(item.getValue().relic());
        Agent a = agents.get(item.getKey());
        if (r != null
            && r.owner.equals(item.getKey())
            && r.state == RelicState.RAW
            && a.station == Station.WORKSHOP
            && a.hauntedUntil <= elapsedMs) {
          r.state = RelicState.SEALED;
          r.protectedUntil = elapsedMs + 30000;
          log(
              "SEAL",
              item.getKey(),
              r.id,
              r.name + " sealed. Take it to the Exit.",
              Visibility.PUBLIC);
        }
        seals.remove(item.getKey());
      }
    for (Task t : new ArrayList<>(tasks.values()))
      if (elapsedMs >= t.endsAt) {
        tasks.remove(t.id);
        if (t.active()) {
          changeAlarm(5);
          log(
              "TASK_TIMEOUT",
              "",
              t.station.name(),
              "A puzzle timed out. Alarm +5.",
              Visibility.PUBLIC);
        }
      }
    offers.entrySet().removeIf(item -> item.getValue().expiresAt() <= elapsedMs);
    observations.removeIf(o -> elapsedMs - o.atMs() > 90000);
    if (vote != null) resolveVote(elapsedMs >= vote.endsAt);
    if (elapsedMs >= nextEvent && nextEvent <= 360000) {
      int ordinal = nextEvent / 120000 - 1, offset = Math.floorMod((int) seed, 3);
      roomEvent = new String[] {"BLACKOUT", "OVERCLOCK", "LOCKDOWN"}[(ordinal + offset) % 3];
      eventUntil = elapsedMs + 20000;
      lockedStation =
          Math.floorMod(Objects.hash(seed, nextEvent), 2) == 0 ? Station.VAULT : Station.WORKSHOP;
      log(
          "EVENT",
          "",
          roomEvent,
          roomEvent.equals("LOCKDOWN")
              ? "Lockdown: "
                  + lockedStation.name().toLowerCase(Locale.ROOT)
                  + " closed for 20 seconds."
              : roomEvent.equals("BLACKOUT")
                  ? "Blackout: signal clues hidden for 20 seconds."
                  : "Overclock: new puzzles receive more time and a hint for 20 seconds.",
          Visibility.PUBLIC);
      nextEvent += 120000;
    }
    checkEnd();
    return "";
  }

  private void checkEnd() {
    if (phase != Phase.ENDED) {
      if (alarm >= MAX_ALARM) end("GHOST", "Alarm reached 100. The ghost sealed the facility.");
      else if (remainingMs() == 0) end("GHOST", "Time ran out before the crew could escape.");
    }
  }

  private void end(String winner, String reason) {
    this.winner = winner;
    endReason = reason;
    phase = Phase.ENDED;
    vote = null;
    tasks.clear();
    seals.clear();
    offers.clear();
    log("END", "", "", reason, Visibility.PUBLIC);
  }

  public void abort(String reason) {
    if (phase != Phase.ENDED) end("NONE", reason);
  }

  private void log(String code, String actor, String target, String text, Visibility visibility) {
    timeline.add(new Entry(elapsedMs, code, actor, target, text, visibility));
    if (timeline.size() > 128) timeline.remove(0);
  }

  public byte[] encode() {
    return Wire.bytes(
        o -> {
          o.writeInt(VERSION);
          o.writeInt(players.size());
          for (Player p : players) {
            Wire.string(o, p.id());
            Wire.string(o, p.name());
          }
          Wire.string(o, ghost);
          o.writeLong(seed);
          o.writeInt(phase.ordinal());
          for (int v :
              new int[] {
                elapsedMs,
                alarm,
                charges,
                abilityReadyAt,
                accusationsLeft,
                missingMs,
                nextTask,
                regenAt,
                purgeReadyAt,
                nextEvent,
                eventUntil
              }) o.writeInt(v);
          o.writeBoolean(exposed);
          o.writeBoolean(paused);
          Wire.string(o, winner);
          Wire.string(o, endReason);
          Wire.string(o, roomEvent);
          o.writeInt(lockedStation.ordinal());
          Wire.strings(o, cleared);
          Wire.strings(o, applied);
          for (Player p : players) {
            Agent a = agents.get(p.id());
            o.writeInt(a.specialty.ordinal());
            o.writeInt(a.station.ordinal());
            for (int v :
                new int[] {
                  a.visitedAt, a.perkReadyAt, a.shieldUntil, a.hauntedUntil, a.immuneUntil
                }) o.writeInt(v);
          }
          for (Relic r : relics.values()) {
            Wire.string(o, r.id);
            o.writeInt(r.state.ordinal());
            o.writeInt(r.location.ordinal());
            Wire.string(o, r.owner);
            o.writeInt(r.heldAt);
            o.writeInt(r.protectedUntil);
          }
          for (Station s : Station.values()) {
            o.writeInt(jamUntil.getOrDefault(s, 0));
            o.writeInt(jamReadyAt.getOrDefault(s, 0));
          }
          o.writeInt(tasks.size());
          for (Task t : tasks.values()) {
            for (String v : List.of(t.id, t.kind, t.operator, t.reader, t.relic)) Wire.string(o, v);
            o.writeInt(t.station.ordinal());
            for (int v : new int[] {t.createdAt, t.endsAt, t.code, t.hints}) o.writeInt(v);
          }
          o.writeInt(seals.size());
          for (var e : seals.entrySet()) {
            Wire.string(o, e.getKey());
            Wire.string(o, e.getValue().relic());
            o.writeInt(e.getValue().finishesAt());
          }
          o.writeInt(offers.size());
          for (Offer f : offers.values()) {
            Wire.string(o, f.relic());
            Wire.string(o, f.from());
            Wire.string(o, f.to());
            o.writeInt(f.expiresAt());
          }
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
            Wire.string(o, a.observer());
            o.writeInt(a.interval());
            o.writeInt(a.atMs());
            o.writeInt(a.band());
            Wire.strings(o, a.nearby());
          }
          o.writeInt(timeline.size());
          for (Entry entry : timeline) {
            o.writeInt(entry.atMs());
            Wire.string(o, entry.code());
            Wire.string(o, entry.actor());
            Wire.string(o, entry.target());
            Wire.string(o, entry.detail());
            o.writeInt(entry.visibility().ordinal());
          }
        });
  }

  private static <T> T ordinal(T[] values, int n) throws IOException {
    if (n < 0 || n >= values.length) throw new IOException("Invalid enum value");
    return values[n];
  }

  public static Game decode(byte[] bytes) throws IOException {
    DataInputStream i = Wire.input(bytes);
    if (i.readInt() != VERSION) throw new IOException("Update all phones to Haunted Heist 2.0");
    int n = Wire.count(i, 8);
    List<Player> players = new ArrayList<>();
    for (int j = 0; j < n; j++) players.add(new Player(Wire.string(i), Wire.string(i)));
    Game g;
    try {
      g = new Game(players, Wire.string(i), i.readLong());
    } catch (IllegalArgumentException e) {
      throw new IOException(e);
    }
    g.phase = ordinal(Phase.values(), i.readInt());
    g.elapsedMs = i.readInt();
    g.alarm = i.readInt();
    g.charges = i.readInt();
    g.abilityReadyAt = i.readInt();
    g.accusationsLeft = i.readInt();
    g.missingMs = i.readInt();
    g.nextTask = i.readInt();
    g.regenAt = i.readInt();
    g.purgeReadyAt = i.readInt();
    g.nextEvent = i.readInt();
    g.eventUntil = i.readInt();
    g.exposed = i.readBoolean();
    g.paused = i.readBoolean();
    g.winner = Wire.string(i);
    g.endReason = Wire.string(i);
    g.roomEvent = Wire.string(i);
    g.lockedStation = ordinal(Station.values(), i.readInt());
    g.cleared.addAll(Wire.strings(i, 8));
    g.applied.addAll(Wire.strings(i, 512));
    for (Player p : players) {
      Agent a = g.agents.get(p.id());
      a.specialty = ordinal(Specialty.values(), i.readInt());
      a.station = ordinal(Station.values(), i.readInt());
      a.visitedAt = i.readInt();
      a.perkReadyAt = i.readInt();
      a.shieldUntil = i.readInt();
      a.hauntedUntil = i.readInt();
      a.immuneUntil = i.readInt();
    }
    Set<String> relicIds = new HashSet<>();
    for (int j = 0; j < 3; j++) {
      Relic r = g.relics.get(Wire.string(i));
      if (r == null || !relicIds.add(r.id)) throw new IOException("Invalid relic");
      r.state = ordinal(RelicState.values(), i.readInt());
      r.location = ordinal(Station.values(), i.readInt());
      r.owner = Wire.string(i);
      r.heldAt = i.readInt();
      r.protectedUntil = i.readInt();
      if (!r.owner.isEmpty() && !g.agents.containsKey(r.owner))
        throw new IOException("Unknown carrier");
    }
    for (Station s : Station.values()) {
      g.jamUntil.put(s, i.readInt());
      g.jamReadyAt.put(s, i.readInt());
    }
    n = Wire.count(i, 3);
    for (int j = 0; j < n; j++) {
      Task t = new Task();
      t.id = Wire.string(i);
      t.kind = Wire.string(i);
      t.operator = Wire.string(i);
      t.reader = Wire.string(i);
      t.relic = Wire.string(i);
      t.station = ordinal(Station.values(), i.readInt());
      t.createdAt = i.readInt();
      t.endsAt = i.readInt();
      t.code = i.readInt();
      t.hints = i.readInt();
      if (!g.agents.containsKey(t.operator)
          || (!t.reader.isEmpty() && !g.agents.containsKey(t.reader))
          || t.code < 0
          || t.code > 63) throw new IOException("Invalid task");
      g.tasks.put(t.id, t);
    }
    n = Wire.count(i, 8);
    for (int j = 0; j < n; j++) g.seals.put(Wire.string(i), new Seal(Wire.string(i), i.readInt()));
    n = Wire.count(i, 3);
    for (int j = 0; j < n; j++) {
      Offer f = new Offer(Wire.string(i), Wire.string(i), Wire.string(i), i.readInt());
      g.offers.put(f.relic(), f);
    }
    if (i.readBoolean()) {
      g.vote = new Vote();
      g.vote.suspect = Wire.string(i);
      g.vote.initiator = Wire.string(i);
      g.vote.endsAt = i.readInt();
      n = Wire.count(i, 8);
      for (int j = 0; j < n; j++) g.vote.ballots.put(Wire.string(i), i.readBoolean());
    }
    n = Wire.count(i, 80);
    for (int j = 0; j < n; j++)
      g.observations.add(
          new Observation(
              Wire.string(i), i.readInt(), i.readInt(), i.readInt(), Wire.strings(i, 8)));
    g.timeline.clear();
    n = Wire.count(i, 128);
    for (int j = 0; j < n; j++)
      g.timeline.add(
          new Entry(
              i.readInt(),
              Wire.string(i),
              Wire.string(i),
              Wire.string(i),
              Wire.string(i),
              ordinal(Visibility.values(), i.readInt())));
    if (i.available() != 0
        || g.elapsedMs < 0
        || g.elapsedMs > ROUND_MS + 5000
        || g.alarm < 0
        || g.alarm > 100
        || g.charges < 0
        || g.charges > 3
        || g.accusationsLeft < 0
        || g.accusationsLeft > 2) throw new IOException("Invalid heist state");
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
