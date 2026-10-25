package com.ghostprotocol.core;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class HuntIntelTest {
  private Game game(String ghost) {
    return new Game(
        List.of(
            new Game.Player("a", "Ada"),
            new Game.Player("b", "Bo"),
            new Game.Player("c", "Cy"),
            new Game.Player("d", "Dee")),
        ghost,
        42);
  }

  @Test
  public void guidanceDoesNotRevealGhost() {
    Game a = game("c"), b = game("d");
    assertEquals(HuntIntel.objective(a, "a"), HuntIntel.objective(b, "a"));
    for (String id : a.ids())
      assertEquals(HuntIntel.inspect(a, "a", id), HuntIntel.inspect(b, "a", id));
  }

  @Test
  public void carriedRelicDeterminesNextObjective() {
    Game g = game("d");
    Game.Relic r = g.relics.get("key");
    r.owner = "a";
    r.state = Game.RelicState.RAW;
    assertTrue(HuntIntel.objective(g, "a").title().contains("Workshop"));
    r.state = Game.RelicState.SEALED;
    assertTrue(HuntIntel.objective(g, "a").title().contains("Exit"));
  }

  @Test
  public void guidanceExplainsTwoDifferentPuzzleRoles() {
    Game g = game("d");
    g.apply(Game.Event.scan("a", Game.Station.VAULT));
    g.apply(Game.Event.task("a", "BREACH", ""));
    assertTrue(HuntIntel.objective(g, "a").detail().contains("join"));
  }

  @Test
  public void privateSabotageDetailsStayOutOfHunterFeed() {
    Game g = game("d");
    g.apply(Game.Event.scan("d", Game.Station.VAULT));
    assertEquals("", g.apply(Game.Event.ability("d", "JAM", "VAULT")));
    assertFalse(
        g.visibleEvents("a", false).stream()
            .anyMatch(e -> e.visibility() == Game.Visibility.GHOST));
    assertTrue(
        g.visibleEvents("d", false).stream()
            .anyMatch(e -> e.visibility() == Game.Visibility.GHOST));
  }
}
