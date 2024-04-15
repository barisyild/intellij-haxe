package com.intellij.plugins.haxe.ide.inlay;

import com.intellij.codeInsight.hints.declarative.InlayHintsProvider;
import com.intellij.plugins.haxe.ide.hint.types.HaxeInlayEnumExtractorHintsProvider;
import com.intellij.plugins.haxe.ide.hint.types.HaxeInlayForLoopHintsProvider;
import org.junit.Test;

public class HaxeForLoopInlayTest extends HaxeInlayTestBase {

  InlayHintsProvider hintsProvider = new HaxeInlayForLoopHintsProvider();

  @Override
  protected  String getBasePath() {
    return "/inlay/haxe.loop.type/";
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
  @Test
  public void testLoopGenerics() throws Exception {
    doTest(hintsProvider);
  }


}
