package com.ghostprotocol.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Lightweight procedural instrument and relic illustrations; animation stops when not visible. */
final class HeistArt extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path jewel = new Path();
  private final Path crown = new Path();
  private String caption = "VAULT   /   WORKSHOP   /   EXIT";
  private Shader halo;
  private String scene = "heist";
  private float phase;
  private ValueAnimator animator;

  HeistArt(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  void setScene(String value) {
    if (!value.equals(scene)) {
      scene = value;
      caption =
          value.contains("crown")
              ? "RELIC / HOLLOW CROWN"
              : value.contains("lens")
                  ? "RELIC / ECHO LENS"
                  : value.contains("key")
                      ? "RELIC / NIGHT KEY"
                      : value.contains("ghost")
                          ? "SIGNAL / COMPROMISED"
                          : "VAULT   /   WORKSHOP   /   EXIT";
      invalidate();
    }
  }

  private void animateIfVisible() {
    boolean enabled =
        isAttachedToWindow()
            && getWindowVisibility() == VISIBLE
            && ValueAnimator.areAnimatorsEnabled()
            && !getContext()
                .getSharedPreferences("accessibility", 0)
                .getBoolean("reduceMotion", false);
    if (enabled && animator == null) {
      animator = ValueAnimator.ofFloat(0, 1);
      animator.setDuration(12000);
      animator.setRepeatCount(ValueAnimator.INFINITE);
      animator.setInterpolator(new android.view.animation.LinearInterpolator());
      animator.addUpdateListener(
          a -> {
            phase = (float) a.getAnimatedValue();
            postInvalidateOnAnimation();
          });
      animator.start();
    } else if (!enabled && animator != null) {
      animator.cancel();
      animator = null;
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    animateIfVisible();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    animateIfVisible();
  }

  @Override
  protected void onDetachedFromWindow() {
    if (animator != null) {
      animator.cancel();
      animator = null;
    }
    super.onDetachedFromWindow();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * .36f;
    if (r <= 0) return;
    halo = new RadialGradient(cx, cy, r * 1.8f, 0x33317874, 0x00111d29, Shader.TileMode.CLAMP);
    jewel.reset();
    jewel.moveTo(cx, cy - r * .55f);
    jewel.lineTo(cx + r * .44f, cy - r * .10f);
    jewel.lineTo(cx + r * .29f, cy + r * .43f);
    jewel.lineTo(cx - r * .29f, cy + r * .43f);
    jewel.lineTo(cx - r * .44f, cy - r * .10f);
    jewel.close();
    crown.reset();
    crown.moveTo(cx - r * .48f, cy + r * .3f);
    crown.lineTo(cx - r * .5f, cy - r * .35f);
    crown.lineTo(cx - r * .22f, cy - r * .04f);
    crown.lineTo(cx, cy - r * .5f);
    crown.lineTo(cx + r * .22f, cy - r * .04f);
    crown.lineTo(cx + r * .5f, cy - r * .35f);
    crown.lineTo(cx + r * .48f, cy + r * .3f);
    crown.close();
  }

  @Override
  protected void onDraw(Canvas c) {
    float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) * .36f;
    paint.setShader(halo);
    paint.setStyle(Paint.Style.FILL);
    c.drawRect(0, 0, w, h, paint);
    paint.setShader(null);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1);
    paint.setColor(0xff2a414c);
    for (int n = 1; n <= 3; n++) c.drawCircle(cx, cy, r * n / 3, paint);
    for (int n = 0; n < 48; n++) {
      double a = n * Math.PI / 24;
      float inner = r + 7, outer = r + (n % 4 == 0 ? 14 : 10);
      c.drawLine(
          cx + (float) Math.cos(a) * inner,
          cy + (float) Math.sin(a) * inner,
          cx + (float) Math.cos(a) * outer,
          cy + (float) Math.sin(a) * outer,
          paint);
    }
    paint.setColor(NativeUi.TEAL);
    paint.setStrokeWidth(2);
    c.drawArc(cx - r, cy - r, cx + r, cy + r, -90 + phase * 360, 80, false, paint);
    paint.setColor(scene.contains("ghost") ? NativeUi.RED : NativeUi.AMBER);
    paint.setStrokeWidth(2);
    if (scene.contains("crown")) {
      c.drawPath(crown, paint);
      c.drawLine(cx - r * .45f, cy + r * .43f, cx + r * .45f, cy + r * .43f, paint);
    } else if (scene.contains("lens")) {
      c.drawCircle(cx, cy, r * .48f, paint);
      c.drawCircle(cx, cy, r * .3f, paint);
      c.drawLine(cx - r * .56f, cy, cx + r * .56f, cy, paint);
      c.drawLine(cx, cy - r * .56f, cx, cy + r * .56f, paint);
    } else if (scene.contains("key")) {
      c.drawCircle(cx - r * .2f, cy - r * .2f, r * .25f, paint);
      c.drawLine(cx - r * .02f, cy - r * .02f, cx + r * .47f, cy + r * .47f, paint);
      c.drawLine(cx + r * .24f, cy + r * .24f, cx + r * .4f, cy + r * .08f, paint);
      c.drawLine(cx + r * .4f, cy + r * .4f, cx + r * .56f, cy + r * .24f, paint);
    } else {
      c.drawPath(jewel, paint);
      c.drawLine(cx, cy - r * .55f, cx, cy + r * .43f, paint);
      c.drawLine(cx - r * .44f, cy - r * .10f, cx + r * .44f, cy - r * .10f, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(NativeUi.TEAL);
    for (int n = 0; n < 3; n++) {
      double a = -Math.PI / 2 + n * Math.PI * 2 / 3;
      float x = cx + (float) Math.cos(a) * r * .91f, y = cy + (float) Math.sin(a) * r * .91f;
      c.drawCircle(x, y, 4, paint);
    }
    paint.setTypeface(Typeface.MONOSPACE);
    paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
    paint.setTextAlign(Paint.Align.LEFT);
    paint.setColor(NativeUi.MUTED);
    c.drawText(caption, 10, h - 6, paint);
  }
}
