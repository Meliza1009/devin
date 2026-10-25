package com.ghostprotocol.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import java.util.*;

/**
 * Small keyed native view binder. Existing controls survive data updates, including scroll/focus.
 */
final class NativeUi {
  static final int BG = 0xff090f16,
      PANEL = 0xff111d29,
      BORDER = 0xff263747,
      TEXT = 0xfff1f4ee,
      MUTED = 0xff9aabb9,
      TEAL = 0xff8df4cd,
      AMBER = 0xffffcf83,
      RED = 0xffff8e98;

  static final class Node {
    final String key, kind;
    String text = "";
    int size = 15, color = TEXT, padding, fill, border, height = -2, gap = 10;
    boolean mono, bold, enabled = true;
    float weight;
    Runnable action;
    final List<Node> children = new ArrayList<>();

    Node(String key, String kind) {
      this.key = key;
      this.kind = kind;
    }

    Node children(Node... nodes) {
      children.addAll(Arrays.asList(nodes));
      return this;
    }

    Node add(Node node) {
      children.add(node);
      return this;
    }

    Node flex() {
      weight = 1;
      return this;
    }

    Node pad(int n) {
      padding = n;
      return this;
    }

    Node gap(int n) {
      gap = n;
      return this;
    }

    Node height(int n) {
      height = n;
      return this;
    }

    Node click(Runnable r) {
      action = r;
      return this;
    }

    Node enabled(boolean value) {
      enabled = value;
      return this;
    }
  }

  static Node col(String key, Node... children) {
    return new Node(key, "col").children(children);
  }

  static Node row(String key, Node... children) {
    return new Node(key, "row").children(children);
  }

  static Node card(String key, Node... children) {
    Node n = col(key, children);
    n.padding = 18;
    n.fill = PANEL;
    n.border = BORDER;
    n.gap = 14;
    return n;
  }

  static Node text(String key, String value, int size, int color) {
    Node n = new Node(key, "text");
    n.text = value;
    n.size = size;
    n.color = color;
    return n;
  }

  static Node title(String key, String value, int size) {
    Node n = text(key, value, size, TEXT);
    n.bold = true;
    return n;
  }

  static Node label(String key, String value, int color) {
    Node n = text(key, value, 11, color);
    n.mono = true;
    return n;
  }

  static Node button(String key, String value, boolean primary, Runnable action) {
    Node n = new Node(key, "button");
    n.text = value;
    n.action = action;
    n.fill = primary ? TEAL : PANEL;
    n.color = primary ? BG : TEXT;
    n.border = primary ? 0 : BORDER;
    n.gap = 10;
    return n;
  }

  static Node input(String key, String initial) {
    Node n = new Node(key, "input");
    n.text = initial;
    n.padding = 16;
    n.fill = PANEL;
    n.border = BORDER;
    return n;
  }

  static Node art(String key, String scene, int height) {
    Node n = new Node(key, "art");
    n.text = scene;
    n.height = height;
    return n;
  }

  private final Context context;
  final LinearLayout parent;
  private final Map<String, View> views = new HashMap<>();
  private final Map<String, String> styles = new HashMap<>();
  private boolean controlPressed;
  private List<Node> deferred;

  NativeUi(Context context, LinearLayout parent) {
    this.context = context;
    this.parent = parent;
  }

  int dp(float n) {
    return Math.round(n * context.getResources().getDisplayMetrics().density);
  }

  View view(String key) {
    return views.get(key);
  }

  void setText(String key, String value) {
    View v = views.get(key);
    if (v instanceof TextView t && !value.contentEquals(t.getText())) t.setText(value);
  }

  void render(List<Node> nodes) {
    if (controlPressed) {
      deferred = nodes;
      return;
    }
    deferred =
        null; // A newer render after release supersedes any snapshot queued during the press.
    Set<String> used = new HashSet<>();
    bind(parent, nodes, used);
    views.keySet().removeIf(k -> !used.contains(k));
    styles.keySet().retainAll(used);
  }

  @android.annotation.SuppressLint(
      "ClickableViewAccessibility") // Returns false: native Button/EditText perform their own
  // clicks, including accessibility actions.
  private void bind(LinearLayout container, List<Node> nodes, Set<String> used) {
    int index = 0;
    for (Node node : nodes) {
      if (!used.add(node.key))
        throw new IllegalArgumentException("Duplicate view key: " + node.key);
      View view = views.get(node.key);
      boolean created = view == null;
      if (created) {
        switch (node.kind) {
          case "col":
          case "row":
            LinearLayout l = new LinearLayout(context);
            l.setOrientation(
                node.kind.equals("row") ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            l.setGravity(node.kind.equals("row") ? Gravity.CENTER_VERTICAL : Gravity.NO_GRAVITY);
            view = l;
            break;
          case "button":
            Button b = new Button(context);
            b.setAllCaps(false);
            b.setStateListAnimator(null);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(dp(52));
            b.setPadding(dp(14), dp(12), dp(14), dp(12));
            view = b;
            break;
          case "input":
            EditText e = new EditText(context);
            e.setSingleLine(true);
            e.setHint("Your callsign");
            e.setHintTextColor(MUTED);
            e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(24)});
            e.setText(node.text);
            e.setSelectAllOnFocus(true);
            view = e;
            break;
          case "art":
            view = new HeistArt(context);
            break;
          default:
            TextView t = new TextView(context);
            t.setLineSpacing(dp(3), 1);
            view = t;
        }
        views.put(node.key, view);
        view.setTag(node.key);
        if (node.kind.equals("button") || node.kind.equals("input"))
          view.setOnTouchListener(
              (v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) controlPressed = true;
                else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                  controlPressed = false;
                  parent.post(
                      () -> {
                        if (deferred != null) {
                          List<Node> pending = deferred;
                          deferred = null;
                          render(pending);
                        }
                      });
                }
                return false;
              });
      }
      if (view.getParent() != container || container.indexOfChild(view) != index) {
        if (view.getParent() instanceof ViewGroup old) old.removeView(view);
        container.addView(view, index);
      }
      String style =
          node.padding
              + "/"
              + node.fill
              + "/"
              + node.border
              + "/"
              + node.height
              + "/"
              + node.weight
              + "/"
              + node.gap
              + "/"
              + node.kind;
      if (!style.equals(styles.get(node.key))) {
        int width =
            node.weight > 0 ? 0 : container.getOrientation() == LinearLayout.HORIZONTAL ? -2 : -1;
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(
                width, node.height >= 0 ? dp(node.height) : node.height, node.weight);
        lp.bottomMargin = dp(node.gap);
        if (container.getOrientation() == LinearLayout.HORIZONTAL) {
          lp.bottomMargin = 0;
          if (index > 0) lp.leftMargin = dp(8);
        }
        view.setLayoutParams(lp);
        if (!node.kind.equals("button"))
          view.setPadding(dp(node.padding), dp(node.padding), dp(node.padding), dp(node.padding));
        if (node.fill != 0) {
          GradientDrawable shape = new GradientDrawable();
          shape.setColor(node.fill);
          shape.setCornerRadius(dp(18));
          if (node.border != 0) shape.setStroke(dp(1), node.border);
          view.setBackground(
              node.kind.equals("button")
                  ? new RippleDrawable(ColorStateList.valueOf(0x336c91a1), shape, null)
                  : shape);
        }
        styles.put(node.key, style);
      }
      view.setEnabled(node.enabled);
      view.setAlpha(node.enabled ? 1f : .5f);
      if (view instanceof TextView t) {
        if (!node.kind.equals("input") && !node.text.contentEquals(t.getText()))
          t.setText(node.text);
        t.setTextColor(node.color);
        t.setTextSize(node.size);
        t.setTypeface(
            node.mono
                ? Typeface.MONOSPACE
                : Typeface.create(
                    node.bold || node.kind.equals("button") ? "sans-serif-medium" : "sans-serif",
                    Typeface.NORMAL));
      }
      if (view instanceof HeistArt art) art.setScene(node.text);
      if (node.action != null)
        view.setOnClickListener(
            v -> {
              v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
              node.action.run();
            });
      else if (!node.kind.equals("input")) {
        view.setOnClickListener(null);
        view.setClickable(false);
      }
      if (view instanceof LinearLayout layout) bind(layout, node.children, used);
      index++;
    }
    while (container.getChildCount() > index) container.removeViewAt(container.getChildCount() - 1);
  }
}
