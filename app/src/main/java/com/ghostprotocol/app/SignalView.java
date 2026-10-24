package com.ghostprotocol.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import com.ghostprotocol.core.Game;
import java.util.*;

/** Abstract signal instrument. It deliberately does not claim to locate a person. */
public final class SignalView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int lime = Color.rgb(194, 249, 112), muted = Color.rgb(73, 100, 81);
  private Game game;
  private String self = "";
  private int band;
  private boolean graph;

  public SignalView(Context c) {
    super(c);
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  public void setSignal(int band) {
    this.band = band;
    graph = false;
    invalidate();
  }

  public void setGraph(Game game, String self) {
    this.game = game;
    this.self = self;
    graph = true;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) * .40f;
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1));
    for (int k = 1; k <= 3; k++) {
      paint.setColor(k == 3 ? 0xff324337 : 0xff253229);
      c.drawCircle(cx, cy, r * k / 3, paint);
    }
    paint.setColor(0xff253229);
    c.drawLine(cx - r - dp(9), cy, cx + r + dp(9), cy, paint);
    c.drawLine(cx, cy - r - dp(9), cx, cy + r + dp(9), paint);
    if (graph && game != null) {
      drawGraph(c, cx, cy, r);
      return;
    }
    paint.setColor(lime);
    paint.setStrokeWidth(dp(2));
    c.drawArc(cx - r, cy - r, cx + r, cy + r, -103, 56 + band * 34, false, paint);
    paint.setColor(muted);
    paint.setStrokeWidth(dp(1));
    for (int k = 0; k < 48; k++) {
      double a = k * Math.PI / 24;
      float x = (float) Math.cos(a), y = (float) Math.sin(a);
      c.drawLine(
          cx + x * (r + dp(5)),
          cy + y * (r + dp(5)),
          cx + x * (r + dp(k % 4 == 0 ? 11 : 8)),
          cy + y * (r + dp(k % 4 == 0 ? 11 : 8)),
          paint);
    }
    paint.setColor(lime);
    paint.setStyle(Paint.Style.FILL);
    paint.setShadowLayer(dp(16), 0, 0, 0x55c2f970);
    c.drawCircle(cx, cy, dp(4 + band * 2), paint);
    paint.clearShadowLayer();
    for (int k = 0; k < 3 + band; k++) {
      double a = 1.2 + k * 2.399;
      float rr = r * (.43f + (k % 3) * .17f);
      paint.setColor(k % 2 == 0 ? lime : muted);
      c.drawCircle(
          cx + (float) Math.cos(a) * rr,
          cy + (float) Math.sin(a) * rr,
          dp(k % 2 == 0 ? 3 : 2),
          paint);
    }
    paint.setTypeface(Typeface.MONOSPACE);
    paint.setTextSize(dp(9));
    paint.setColor(0xff7b9682);
    paint.setTextAlign(Paint.Align.LEFT);
    c.drawText("RX / LOCAL", dp(4), h - dp(3), paint);
    paint.setTextAlign(Paint.Align.RIGHT);
    c.drawText("∿ ENCRYPTED", w - dp(4), h - dp(3), paint);
  }

  private void drawGraph(Canvas c, float cx, float cy, float r) {
    Map<String, PointF> positions = new HashMap<>();
    int index = 0, n = game.players.size();
    for (Game.Player p : game.players) {
      double a = -Math.PI / 2 + 2 * Math.PI * index++ / n;
      positions.put(
          p.id(),
          new PointF(cx + (float) Math.cos(a) * r * .8f, cy + (float) Math.sin(a) * r * .8f));
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1));
    Set<String> edges = new HashSet<>();
    for (Game.Observation o : game.observations)
      if (o.band() > 0)
        for (String id : o.nearby()) {
          String key = o.observer().compareTo(id) < 0 ? o.observer() + id : id + o.observer();
          if (!edges.add(key)) continue;
          PointF a = positions.get(o.observer()), b = positions.get(id);
          if (a != null && b != null) {
            paint.setColor(0x664d704e);
            c.drawLine(a.x, a.y, b.x, b.y, paint);
          }
        }
    for (Game.Player p : game.players) {
      PointF point = positions.get(p.id());
      boolean eligible = game.evidence(p.id()).eligible();
      paint.setColor(p.id().equals(self) ? lime : eligible ? 0xffe6b970 : 0xff8ca093);
      paint.setStyle(Paint.Style.FILL);
      c.drawCircle(point.x, point.y, dp(7), paint);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      paint.setTextSize(dp(11));
      c.drawText(p.id().equals(self) ? "You" : p.name(), point.x, point.y + dp(23), paint);
    }
  }

  private float dp(float v) {
    return v * getResources().getDisplayMetrics().density;
  }
}
