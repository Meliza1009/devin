package com.ghostprotocol.core;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class GameTest {
  private Game game() {
    return new Game(
        List.of(
            new Game.Player("a", "Ada"),
            new Game.Player("b", "Bo"),
            new Game.Player("c", "Cy"),
            new Game.Player("d", "Dee")),
        "d",
        42);
  }

  private void advance(Game g, int seconds) {
    for (int i = 0; i < seconds; i++) g.apply(Game.Event.tick(1000, g.ids()));
  }

  private void evidence(Game g, String target) {
    for (int i = 0; i < 3; i++) {
      g.apply(Game.Event.observation("a", 2, List.of(target)));
      g.apply(Game.Event.observation("b", 2, List.of(target)));
      if (i < 2) advance(g, 10);
    }
  }

  @Test
  public void calibrationBlocksTagsAndExpires() {
    Game g = game();
    assertEquals(Game.Phase.CALIBRATING, g.phase);
    assertFalse(g.apply(Game.Event.tag("a", "d")).isEmpty());
    advance(g, 30);
    assertEquals(Game.Phase.HUNT, g.phase);
    assertEquals(450000, g.remainingMs());
  }

  @Test
  public void evidenceNeedsIndependentObserversAndDistinctIntervals() {
    Game g = game();
    advance(g, 30);
    for (int i = 0; i < 4; i++) {
      g.apply(Game.Event.observation("a", 2, List.of("d")));
      advance(g, 10);
    }
    assertFalse(g.evidence("d").eligible());
    g.apply(Game.Event.observation("b", 2, List.of("d")));
    assertTrue(g.evidence("d").eligible());
    advance(g, 91);
    assertFalse(g.evidence("d").eligible());
  }

  @Test
  public void correctConvictionNeedsMajorityAndNearbyTag() {
    Game g = game();
    advance(g, 30);
    evidence(g, "d");
    assertFalse(g.apply(Game.Event.tag("c", "d")).isEmpty());
    assertEquals("", g.apply(Game.Event.tag("a", "d")));
    assertEquals(Game.Phase.HUNT, g.phase);
    g.apply(Game.Event.vote("b", true));
    assertEquals(Game.Phase.ENDED, g.phase);
    assertEquals("HUNTERS", g.winner);
  }

  @Test
  public void wrongConvictionClearsSuspectAndCostsTime() {
    Game g = game();
    advance(g, 30);
    evidence(g, "c");
    int before = g.remainingMs();
    g.apply(Game.Event.tag("a", "c"));
    g.apply(Game.Event.vote("b", true));
    assertTrue(g.cleared.contains("c"));
    assertEquals(before - 45000, g.remainingMs());
    assertEquals(3, g.charges);
    assertTrue(g.cooldownUntil > g.elapsedMs);
    advance(g, 31);
    assertFalse(g.apply(Game.Event.tag("a", "c")).isEmpty());
  }

  @Test
  public void abstentionsFailVoteAndNoRoleIsRevealed() {
    Game g = game();
    advance(g, 30);
    evidence(g, "c");
    g.apply(Game.Event.tag("a", "c"));
    advance(g, 15);
    assertNull(g.vote);
    assertTrue(g.cleared.isEmpty());
    assertEquals(0, g.penaltyMs);
  }

  @Test
  public void ghostAbilitiesUseChargesCooldownAndRecentEncounters() {
    Game g = game();
    advance(g, 30);
    assertFalse(g.apply(Game.Event.ability("a", "FADE", "")).isEmpty());
    assertFalse(g.apply(Game.Event.ability("d", "ECHO", "b")).isEmpty());
    g.apply(Game.Event.observation("d", 1, List.of("b")));
    assertEquals("", g.apply(Game.Event.ability("d", "ECHO", "b")));
    assertEquals(1, g.charges);
    assertFalse(g.apply(Game.Event.ability("d", "FADE", "")).isEmpty());
    advance(g, 31);
    assertEquals("", g.apply(Game.Event.ability("d", "FADE", "")));
    assertEquals(0, g.charges);
  }

  @Test
  public void timerExpiryOverridesUnresolvedVote() {
    Game g = game();
    advance(g, 455);
    evidence(g, "d");
    assertEquals("", g.apply(Game.Event.tag("a", "d")));
    assertNotNull(g.vote);
    advance(g, 5);
    assertEquals(Game.Phase.ENDED, g.phase);
    assertEquals("GHOST", g.winner);
  }

  @Test
  public void missingPhonePausesThenAborts() {
    Game g = game();
    advance(g, 30);
    int before = g.remainingMs();
    for (int i = 0; i < 10; i++) g.apply(Game.Event.tick(1000, Set.of("a", "b", "c")));
    assertTrue(g.paused);
    assertEquals(before, g.remainingMs());
    for (int i = 0; i < 60; i++) g.apply(Game.Event.tick(1000, Set.of("a", "b", "c")));
    assertEquals("NONE", g.winner);
  }

  @Test
  public void serializationIsCanonicalAndResumable() throws Exception {
    Game g = game();
    advance(g, 30);
    evidence(g, "c");
    g.apply(Game.Event.tag("a", "c"));
    Game restored = Game.decode(g.encode());
    assertArrayEquals(g.encode(), restored.encode());
    restored.apply(Game.Event.vote("b", true));
    g.apply(Game.Event.vote("b", true));
    assertArrayEquals(g.encode(), restored.encode());
  }

  @Test
  public void inputValidationDoesNotChangeState() throws Exception {
    Game g = game();
    byte[] before = g.encode();
    assertFalse(g.apply(Game.Event.observation("outsider", 2, List.of("d"))).isEmpty());
    assertArrayEquals(before, g.encode());
  }

  @Test
  public void firstReadingRequiresACompleteTenSecondInterval() {
    Game g = game();
    advance(g, 9);
    assertFalse(g.apply(Game.Event.observation("a", 2, List.of("d"))).isEmpty());
    advance(g, 1);
    assertEquals("", g.apply(Game.Event.observation("a", 2, List.of("d"))));
  }

  @Test
  public void eightPlayersNeedFourVotesToConvict() {
    List<Game.Player> players = new java.util.ArrayList<>();
    for (char id = 'a'; id <= 'h'; id++)
      players.add(new Game.Player(String.valueOf(id), String.valueOf(id)));
    Game g = new Game(players, "h", 42);
    advance(g, 30);
    evidence(g, "h");
    assertEquals("", g.apply(Game.Event.tag("a", "h")));
    g.apply(Game.Event.vote("b", true));
    g.apply(Game.Event.vote("c", true));
    assertEquals(Game.Phase.HUNT, g.phase);
    assertFalse(g.apply(Game.Event.vote("h", true)).isEmpty());
    g.apply(Game.Event.vote("d", true));
    assertEquals("HUNTERS", g.winner);
  }
}
