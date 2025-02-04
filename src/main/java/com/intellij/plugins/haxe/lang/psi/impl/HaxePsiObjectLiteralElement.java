/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 TiVo Inc.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2017-2018 Ilya Malanin
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
package com.intellij.plugins.haxe.lang.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.lang.psi.HaxeComponentName;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;

@CustomLog
public abstract class HaxePsiObjectLiteralElement extends HaxePsiFieldImpl {


  public HaxePsiObjectLiteralElement(ASTNode node) {
    super(node);
  }

  @Override
  public @NotNull String getName() {
    HaxeComponentName componentName = getComponentName();
    if (componentName != null) {
      return componentName.getIdentifier().getText();
    }
    return "<unnamed>";
  }
}
