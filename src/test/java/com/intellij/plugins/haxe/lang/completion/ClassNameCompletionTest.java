/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.lang.completion;

import org.junit.Test;

/**
 * @author: Fedor.Korotkov
 */
public class ClassNameCompletionTest extends HaxeCompletionTestBase {
  public ClassNameCompletionTest() {
    super("completion", "types");
  }

  @Override
  protected void doTest() throws Throwable {
    myFixture.configureByFiles(getTestName(false) + ".hx", "com/Foo.hx", "com/bar/Foo.hx", "com/bar/IBar.hx");
    doTestVariantsInner(getTestName(false) + ".txt");
  }

  @Test
  public void testExtends() throws Throwable {
    doTest();
  }

  @Test
  public void testImplements() throws Throwable {
    doTest();
  }

  @Test
  public void testMethod() throws Throwable {
    doTest();
  }

  @Test
  public void testTypeParameter() throws Throwable {
    doTest();
  }

  @Test
  public void testClassHelper() throws Throwable {
    doTest();
  }
}