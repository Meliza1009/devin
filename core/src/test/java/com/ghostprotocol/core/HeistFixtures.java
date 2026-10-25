package com.ghostprotocol.core;

import static org.junit.Assert.*;

import java.util.*;

abstract class HeistFixtures {
  Game game() {
    return new Game(
        List.of(
            new Game.Player("a", "Ada"),
            new Game.Player("b", "Bo"),
            new Game.Player("c", "Cy"),
            new Game.Player("d", "Dee")),
        "d",
        42);
  }

  void ok(Game g, Game.Event e) {
    assertEquals("", g.apply(e));
  }

  void advance(Game g, int seconds) {
    for (int n = 0; n < seconds; n++) ok(g, Game.Event.tick(1000, g.ids()));
  }

  void near(Game g, String a, String b) {
    if (g.elapsedMs < 10000) advance(g, 10);
    ok(g, Game.Event.observation(a, 1, List.of(b)));
  }

  void pair(Game g, Game.Station station, String kind, String target) {
    ok(g, Game.Event.scan("a", station));
    ok(g, Game.Event.scan("b", station));
    if (!g.recentlyNear("a", "b")) near(g, "a", "b");
    ok(g, Game.Event.task("a", kind, target));
    Game.Task task = g.taskFor("a");
    assertNotNull(task);
    ok(g, Game.Event.join("b", task.id));
  }

  void solve(Game g) {
    Game.Task t = g.taskFor("a");
    ok(g, Game.Event.answer("a", t.id, t.answerCode()));
  }
}
