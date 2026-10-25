package com.ghostprotocol.core;

/** Static checkpoint cards are reusable; they contain no room key, player ID, or network URL. */
public final class CheckpointCode {
  private CheckpointCode() {}

  public static String encode(Game.Station station) {
    if (station == Game.Station.NONE) throw new IllegalArgumentException("Choose a checkpoint");
    return "ghostprotocol:2:station:" + station.name();
  }

  public static Game.Station decode(String text) {
    for (Game.Station s : Game.Station.values())
      if (s != Game.Station.NONE && encode(s).equals(text)) return s;
    throw new IllegalArgumentException("Use a Ghost Protocol 2.0 checkpoint card.");
  }
}
