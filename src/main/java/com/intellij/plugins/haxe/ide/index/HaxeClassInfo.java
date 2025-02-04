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
package com.intellij.plugins.haxe.ide.index;

import com.intellij.plugins.haxe.HaxeComponentType;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * @author: Fedor.Korotkov
 */
@Getter
@EqualsAndHashCode
public class HaxeClassInfo {
  @NotNull private final String path;
  @NotNull private final String name;
  @Nullable private final HaxeComponentType type;

  public HaxeClassInfo(@NotNull String name, @NotNull String path, @Nullable HaxeComponentType type) {
    this.name = name;
    this.path = path;
    this.type = type;
  }


  @Nullable
  public Icon getIcon() {
    return type == null ? null : type.getIcon();
  }

}
