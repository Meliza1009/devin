package com.ghostprotocol.core;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class HeistEdgeTest extends HeistFixtures {
  private Game.Relic recover(Game g) {
    pair(g, Game.Station.VAULT, "BREACH", "");
    solve(g);
    return g.inventory("a").get(0);
  }

  private void seal(Game g, Game.Relic r) {
    ok(g, Game.Event.scan("a", Game.Station.WORKSHOP));
    ok(g, Game.Event.seal("a", r.id));
    advance(g, 6);
  }

  @Test
  public void corruptionRespectsResealProtectionAndWards() {
    Game g = game();
    Game.Relic r = recover(g);
    seal(g, r);
    advance(g, 4);
    ok(g, Game.Event.observation("d", 1, List.of("a")));
    assertFalse(g.apply(Game.Event.ability("d", "CORRUPT", r.id)).isEmpty());
    advance(g, 30);
    ok(g, Game.Event.observation("d", 1, List.of("a")));
    g.agents.get("a").shieldUntil = g.elapsedMs + 30000;
    assertFalse(g.apply(Game.Event.ability("d", "CORRUPT", r.id)).isEmpty());
    advance(g, 30);
    ok(g, Game.Event.observation("d", 1, List.of("a")));
    ok(g, Game.Event.ability("d", "CORRUPT", r.id));
    assertEquals(Game.RelicState.RAW, r.state);
    seal(g, r);
    assertTrue(r.protectedUntil > g.elapsedMs);
  }

  @Test
  public void hauntingInterruptsSealingAndCannotPermanentlyLockPlayer() {
    Game g = game();
    Game.Relic r = recover(g);
    advance(g, 80);
    ok(g, Game.Event.tag("a", "d"));
    ok(g, Game.Event.vote("b", true));
    ok(g, Game.Event.observation("d", 1, List.of("a")));
    ok(g, Game.Event.scan("a", Game.Station.WORKSHOP));
    ok(g, Game.Event.seal("a", r.id));
    ok(g, Game.Event.ability("d", "HAUNT", "a"));
    assertTrue(g.seals.isEmpty());
    advance(g, 20);
    ok(g, Game.Event.observation("d", 1, List.of("a")));
    assertFalse(g.apply(Game.Event.ability("d", "HAUNT", "a")).isEmpty());
    ok(g, Game.Event.scan("a", Game.Station.WORKSHOP));
    ok(g, Game.Event.seal("a", r.id));
    advance(g, 6);
    assertEquals(Game.RelicState.SEALED, r.state);
  }

  @Test
  public void abandonedLootCanBeRecalledWithoutDuplication() {
    Game g = game();
    Game.Relic r = recover(g);
    advance(g, 60);
    pair(g, Game.Station.VAULT, "RECALL", r.id);
    solve(g);
    assertEquals(Game.RelicState.VAULT, r.state);
    assertEquals("", r.owner);
    assertEquals(10, g.alarm);
    assertEquals(3, g.relics.size());
  }

  @Test
  public void exposureReturnsGhostLootToWorkshopStorage() {
    Game g = game();
    Game.Relic r = recover(g);
    r.owner = "d";
    advance(g, 80);
    ok(g, Game.Event.tag("a", "d"));
    ok(g, Game.Event.vote("b", true));
    assertEquals("", r.owner);
    assertEquals(Game.Station.WORKSHOP, r.location);
    ok(g, Game.Event.scan("b", Game.Station.WORKSHOP));
    ok(g, Game.Event.take("b", r.id));
    assertEquals("b", r.owner);
  }

  @Test
  public void expiredHandoffNeverMovesRelic() {
    Game g = game();
    Game.Relic r = recover(g);
    ok(g, Game.Event.offer("a", r.id, "b"));
    advance(g, 16);
    assertFalse(g.apply(Game.Event.accept("b", r.id)).isEmpty());
    assertEquals("a", r.owner);
  }

  @Test
  public void engineerRepairsJamWithoutBlockingOtherSpecialties() {
    Game g = game();
    ok(g, Game.Event.scan("d", Game.Station.VAULT));
    ok(g, Game.Event.ability("d", "JAM", "VAULT"));
    String engineer =
        g.agents.entrySet().stream()
            .filter(e -> e.getValue().specialty == Game.Specialty.ENGINEER)
            .findFirst()
            .get()
            .getKey();
    ok(g, Game.Event.scan(engineer, Game.Station.VAULT));
    ok(g, Game.Event.perk(engineer, ""));
    assertTrue(g.stationOpen(Game.Station.VAULT));
    assertFalse(g.apply(Game.Event.perk(engineer, "")).isEmpty());
  }

  @Test
  public void courierCapacityAndRecipientConsentAreEnforced() {
    Game g = game();
    Game.Relic r = recover(g);
    g.agents.get("a").specialty = Game.Specialty.COURIER;
    pair(g, Game.Station.VAULT, "BREACH", "");
    solve(g);
    assertEquals(2, g.inventory("a").size());
    assertFalse(g.apply(Game.Event.task("a", "BREACH", "")).isEmpty());
    ok(g, Game.Event.offer("a", r.id, "b"));
    assertEquals(2, g.inventory("a").size());
    ok(g, Game.Event.decline("b", r.id));
    assertEquals(2, g.inventory("a").size());
  }

  @Test
  public void activeTasksAndOffersRoundTripInCanonicalState() {
    Game g = game();
    pair(g, Game.Station.VAULT, "BREACH", "");
    assertArrayEquals(g.encode(), g.copy().encode());
    solve(g);
    Game.Relic r = g.inventory("a").get(0);
    ok(g, Game.Event.offer("a", r.id, "b"));
    ok(g, Game.Event.scan("a", Game.Station.WORKSHOP));
    ok(g, Game.Event.seal("a", r.id));
    assertArrayEquals(g.encode(), g.copy().encode());
  }

  @Test
  public void voteTimeoutSpendsOneOpportunityWithoutRevealingAnyone() {
    Game g = game();
    advance(g, 90);
    ok(g, Game.Event.tag("a", "d"));
    advance(g, 20);
    assertNull(g.vote);
    assertFalse(g.exposed);
    assertEquals(1, g.accusationsLeft);
    assertTrue(g.cleared.isEmpty());
  }

  @Test
  public void checkpointParserRejectsUrlsAndOtherVersions() {
    for (Game.Station station : Game.Station.values())
      if (station != Game.Station.NONE)
        assertEquals(station, CheckpointCode.decode(CheckpointCode.encode(station)));
    for (String value :
        List.of(
            "https://example.com",
            "ghostprotocol:1:station:VAULT",
            "ghostprotocol:2:station:NONE")) {
      try {
        CheckpointCode.decode(value);
        fail();
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
