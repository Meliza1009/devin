package com.ghostprotocol.core;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class GameTest extends HeistFixtures {
  @Test
  public void votesRequireStrictMajorityForEightPlayers() {
    List<Game.Player> ps = new ArrayList<>();
    for (int n = 0; n < 8; n++) ps.add(new Game.Player("p" + n, "P" + n));
    Game g = new Game(ps, "p7", 1);
    advance(g, 90);
    ok(g, Game.Event.tag("p0", "p7"));
    ok(g, Game.Event.vote("p1", true));
    ok(g, Game.Event.vote("p2", true));
    assertFalse(g.exposed);
    ok(g, Game.Event.vote("p3", true));
    assertTrue(g.exposed);
  }

  @Test
  public void rejectedActionDoesNotChangeState() {
    Game g = game();
    byte[] before = g.encode();
    assertFalse(g.apply(Game.Event.deposit("a", "key")).isEmpty());
    assertArrayEquals(before, g.encode());
  }

  @Test
  public void firstSignalWaitsForCompleteInterval() {
    Game g = game();
    assertFalse(g.apply(Game.Event.observation("a", 2, List.of("b"))).isEmpty());
    advance(g, 10);
    ok(g, Game.Event.observation("a", 2, List.of("b")));
    assertFalse(g.apply(Game.Event.observation("a", 2, List.of("b"))).isEmpty());
  }

  @Test
  public void observationsExpireAndHistoryIsBounded() {
    Game g = game();
    advance(g, 10);
    ok(g, Game.Event.observation("a", 2, List.of("b")));
    advance(g, 91);
    assertTrue(g.observations.isEmpty());
  }

  @Test
  public void missingPhonesFreezeClockAndAbortAtSeventySeconds() {
    Game g = game();
    for (int n = 0; n < 10; n++) ok(g, Game.Event.tick(1000, Set.of("a", "b", "c")));
    assertTrue(g.paused);
    assertEquals(0, g.elapsedMs);
    for (int n = 0; n < 60; n++) ok(g, Game.Event.tick(1000, Set.of("a", "b", "c")));
    assertEquals("NONE", g.winner);
  }

  @Test
  public void recoveryDoesNotConsumeRoundTime() {
    Game g = game();
    for (int n = 0; n < 12; n++) ok(g, Game.Event.tick(1000, Set.of("a", "b", "c")));
    ok(g, Game.Event.tick(1000, g.ids()));
    assertFalse(g.paused);
    assertEquals(1000, g.elapsedMs);
  }

  @Test
  public void ghostJamRequiresVisitAndRespectsEnergyAndProtection() {
    Game g = game();
    assertFalse(g.apply(Game.Event.ability("d", "JAM", "VAULT")).isEmpty());
    ok(g, Game.Event.scan("d", Game.Station.VAULT));
    ok(g, Game.Event.ability("d", "JAM", "VAULT"));
    assertEquals(1, g.charges);
    assertFalse(g.stationOpen(Game.Station.VAULT));
    advance(g, 25);
    assertTrue(g.stationOpen(Game.Station.VAULT));
    assertFalse(g.apply(Game.Event.ability("d", "JAM", "VAULT")).isEmpty());
  }

  @Test
  public void handoffNeedsConsentCapacityAndProximity() {
    Game g = game();
    pair(g, Game.Station.VAULT, "BREACH", "");
    solve(g);
    Game.Relic r = g.inventory("a").get(0);
    ok(g, Game.Event.offer("a", r.id, "b"));
    assertEquals("a", r.owner);
    ok(g, Game.Event.accept("b", r.id));
    assertEquals("b", r.owner);
    assertTrue(g.offers.isEmpty());
  }

  @Test
  public void failedPuzzleSpendsNoRelicAndRaisesAlarm() {
    Game g = game();
    pair(g, Game.Station.VAULT, "BREACH", "");
    Game.Task t = g.taskFor("a");
    ok(g, Game.Event.answer("a", t.id, (t.answerCode() + 1) % 64));
    assertEquals(5, g.alarm);
    assertTrue(g.inventory("a").isEmpty());
    assertTrue(g.tasks.isEmpty());
  }

  @Test
  public void eventsAreDeterministicNonRepeatingAndExpire() {
    Game g = game();
    Set<String> events = new HashSet<>();
    for (int n = 0; n < 3; n++) {
      advance(g, 120);
      events.add(g.roomEvent);
      assertTrue(g.eventUntil > g.elapsedMs);
    }
    assertEquals(3, events.size());
    assertArrayEquals(g.encode(), g.copy().encode());
  }

  @Test
  public void terminalAbortCannotBeOverwritten() {
    Game g = game();
    g.abort("Gone");
    assertFalse(g.apply(Game.Event.tick(1000, g.ids())).isEmpty());
    assertEquals("NONE", g.winner);
  }

  @Test
  public void activePuzzleTimesOutButWaitingPartnerDoesNotRaiseAlarm() {
    Game g = game();
    ok(g, Game.Event.scan("a", Game.Station.VAULT));
    ok(g, Game.Event.task("a", "BREACH", ""));
    advance(g, 31);
    assertTrue(g.tasks.isEmpty());
    assertEquals(0, g.alarm);
    pair(g, Game.Station.VAULT, "BREACH", "");
    advance(g, 31);
    assertEquals(5, g.alarm);
  }
}
