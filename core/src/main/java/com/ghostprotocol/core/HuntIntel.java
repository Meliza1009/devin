package com.ghostprotocol.core;

import java.util.*;

/** Public-data mission guidance. Never uses secret roles to suggest suspects. */
public final class HuntIntel {
  private HuntIntel() {}

  public record Objective(String step, String title, String detail, String destination) {}

  public record Case(int witnesses, int scans, int strongMatches, int lastSeenAgoMs) {}

  public static Case inspect(Game g, String viewer, String suspect) {
    Game.Evidence evidence = g.evidence(suspect);
    int last = -1, matches = 0;
    for (Game.Observation o : g.history(viewer))
      if (o.nearby().contains(suspect)) {
        last = Math.max(last, o.atMs());
        if (o.band() == 2) matches++;
      }
    return new Case(
        evidence.observers(), evidence.intervals(), matches, last < 0 ? -1 : g.elapsedMs - last);
  }

  public static Objective objective(Game g, String self) {
    if (g.phase == Game.Phase.ENDED)
      return new Objective("DEBRIEF", "The heist is over", g.endReason, "Mission");
    if (g.vote != null)
      return new Objective(
          "CREW DECISION",
          "The room needs a verdict",
          "Review the accusation. A wrong conviction adds 20 Alarm.",
          "Crew");
    Game.Task task = g.taskFor(self);
    if (task != null)
      return new Objective(
          "TEAM TASK",
          task.active()
              ? "Solve the sequence together"
              : "Find a partner at the " + title(task.station.name()),
          task.active()
              ? "The reader has the sequence. The operator enters it. Talk to each other."
              : "Ask another player to scan this checkpoint and join your task.",
          "Mission");
    for (Game.Offer offer : g.offers.values())
      if (offer.to().equals(self))
        return new Objective(
            "HANDOFF",
            "A teammate offered you a relic",
            "Open Gear to accept or decline. Check your free inventory slots.",
            "Gear");
    if (g.seals.containsKey(self))
      return new Objective(
          "SECURE",
          "Sealing your relic",
          "Stay at the Workshop until the sealing action completes.",
          "Mission");
    for (Game.Relic relic : g.inventory(self))
      if (relic.state == Game.RelicState.RAW)
        return new Objective(
            "02 / SECURE",
            "Take your relic to the Workshop",
            "Scan the Workshop card and seal it before heading to the Exit.",
            "Mission");
    if (!g.inventory(self).isEmpty())
      return new Objective(
          "03 / DELIVER",
          "Bring your sealed relic to the Exit",
          "Scan the Exit card and deposit your relic. Delivered loot is safe.",
          "Mission");
    if (g.delivered() == 3)
      return new Objective(
          "04 / ESCAPE",
          "Open the Exit with a partner",
          "All relics are secured. Meet another player at the Exit for the final puzzle.",
          "Mission");
    for (Game.Relic r : g.relics.values())
      if (r.owner.isEmpty() && r.location == Game.Station.WORKSHOP)
        return new Objective(
            "02 / RECOVER",
            "Collect the relic in Workshop storage",
            "A relic is waiting in storage. Scan the Workshop to collect it.",
            "Mission");
    for (Game.Relic r : g.relics.values())
      if (r.state == Game.RelicState.VAULT)
        return new Objective(
            "01 / RECOVER",
            "Find a partner at the Vault",
            "Scan the Vault card. Start a breach and ask a nearby player to join.",
            "Mission");
    return new Objective(
        "SUPPORT",
        "Help the carriers reach the Exit",
        "Trade to share the load, repair jams, or purge Alarm at the Workshop.",
        "Crew");
  }

  public static String title(String value) {
    String s = value.toLowerCase(Locale.ROOT);
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  public static String trend(List<Game.Observation> history) {
    if (history.size() < 2) return "Waiting for a comparison";
    int delta = history.get(history.size() - 1).band() - history.get(history.size() - 2).band();
    return delta > 0 ? "Signal rising" : delta < 0 ? "Signal fading" : "Signal holding";
  }
}
