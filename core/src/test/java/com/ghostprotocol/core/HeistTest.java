package com.ghostprotocol.core;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class HeistTest extends HeistFixtures {
  @Test
  public void completeHeistWinsWithoutIdentifyingTheGhost() {
    Game g = game();
    for (int i = 0; i < 3; i++) {
      pair(g, Game.Station.VAULT, "BREACH", "");
      solve(g);
      Game.Relic r = g.inventory("a").get(0);
      ok(g, Game.Event.scan("a", Game.Station.WORKSHOP));
      ok(g, Game.Event.seal("a", r.id));
      advance(g, 6);
      assertEquals(Game.RelicState.SEALED, r.state);
      ok(g, Game.Event.scan("a", Game.Station.EXIT));
      ok(g, Game.Event.deposit("a", r.id));
    }
    pair(g, Game.Station.EXIT, "EXTRACT", "");
    solve(g);
    assertEquals("HUNTERS", g.winner);
    assertFalse(g.exposed);
  }

  @Test
  public void taskNeedsPhysicalCheckinsAndAnotherNearbyPlayer() {
    Game g = game();
    assertFalse(g.apply(Game.Event.task("a", "BREACH", "")).isEmpty());
    ok(g, Game.Event.scan("a", Game.Station.VAULT));
    ok(g, Game.Event.task("a", "BREACH", ""));
    String task = g.taskFor("a").id;
    assertFalse(g.apply(Game.Event.join("b", task)).isEmpty());
    ok(g, Game.Event.scan("b", Game.Station.VAULT));
    assertFalse(g.apply(Game.Event.join("b", task)).isEmpty());
    near(g, "a", "b");
    ok(g, Game.Event.join("b", task));
    assertFalse(g.apply(Game.Event.answer("b", task, g.taskFor("a").answerCode())).isEmpty());
  }

  @Test
  public void duplicateRewardCannotDuplicateLoot() {
    Game g = game();
    pair(g, Game.Station.VAULT, "BREACH", "");
    Game.Task t = g.taskFor("a");
    Game.Event answer = Game.Event.answer("a", t.id, t.answerCode());
    ok(g, answer);
    ok(g, answer);
    assertEquals(1, g.inventory("a").size());
    assertArrayEquals(g.encode(), g.copy().encode());
  }

  @Test
  public void exposingGhostStartsShowdownRatherThanWinning() {
    Game g = game();
    advance(g, 90);
    ok(g, Game.Event.tag("a", "d"));
    ok(g, Game.Event.vote("b", true));
    assertTrue(g.exposed);
    assertEquals(Game.Phase.SHOWDOWN, g.phase);
    assertEquals("", g.winner);
    assertFalse(g.apply(Game.Event.task("d", "BREACH", "")).isEmpty());
  }

  @Test
  public void wrongAccusationRaisesAlarmAndSpendsWarrant() {
    Game g = game();
    advance(g, 90);
    ok(g, Game.Event.tag("a", "c"));
    ok(g, Game.Event.vote("b", true));
    assertEquals(20, g.alarm);
    assertEquals(1, g.accusationsLeft);
    assertTrue(g.cleared.contains("c"));
  }

  @Test
  public void timerAlarmAndTerminalStateAreAuthoritative() {
    Game g = game();
    advance(g, 600);
    assertEquals("GHOST", g.winner);
    assertFalse(g.apply(Game.Event.scan("a", Game.Station.VAULT)).isEmpty());
    Game other = game();
    other.alarm = 95;
    pair(other, Game.Station.VAULT, "BREACH", "");
    Game.Task task = other.taskFor("a");
    ok(other, Game.Event.answer("a", task.id, (task.answerCode() + 1) % 64));
    assertEquals("GHOST", other.winner);
  }

  @Test
  public void scanningElsewhereCancelsTaskWithoutGivingLoot() {
    Game g = game();
    pair(g, Game.Station.VAULT, "BREACH", "");
    ok(g, Game.Event.scan("b", Game.Station.EXIT));
    assertNull(g.taskFor("a"));
    assertTrue(g.inventory("a").isEmpty());
  }
}
