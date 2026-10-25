package com.ghostprotocol.app;

import android.app.Instrumentation;
import android.os.Bundle;

/** Test-only runner, absent from release DEX. */
public final class SmokeInstrumentation extends Instrumentation {
  private Bundle arguments;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args;
    start();
  }

  @Override
  public void onStart() {
    String mode = arguments == null ? "" : arguments.getString("mode", "");
    if (mode.equals("mesh")) new MeshScenario(this, arguments).run();
    else if (mode.equals("ui-regression")) new UiRegressionScenario(this).run();
    else new HeistUiScenario(this).run();
  }
}
