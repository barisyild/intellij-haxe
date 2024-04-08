package com.intellij.plugins.haxe.ide.inlay;

import com.intellij.codeInsight.hints.declarative.InlayHintsProvider;
import com.intellij.plugins.haxe.ide.hint.types.HaxeInlayCaptureVariableHintsProvider;
import com.intellij.plugins.haxe.ide.hint.types.HaxeInlayEnumExtractorHintsProvider;
import org.junit.Test;

public class HaxeCaptureVariableInlayTest extends HaxeInlayTestBase {

  InlayHintsProvider hintsProvider = new HaxeInlayCaptureVariableHintsProvider();

  @Override
  protected  String getBasePath() {
    return "/inlay/haxe.capture.variable/";
  }

  @Override
  public void setUp() throws Exception {
    useHaxeToolkit();
    super.setUp();
    setTestStyleSettings(2);
  }

  // test to generate preview used in inlay settings example
  @Test
  public void testPreview() throws Exception {
    doTest(hintsProvider);
  }


}
