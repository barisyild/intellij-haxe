/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2018 Eric Bishton
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
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;

import java.util.HashSet;
import java.util.Set;

import com.intellij.openapi.util.Key;

/**
 * This is effectively an alias for the mixin class, except with a more
 * memorable and consistent name.
 *
 * Created by ebishton on 9/28/14.
 */
public abstract class HaxeMethodImpl extends HaxeMethodPsiMixinImpl implements HaxeMethod {
  protected HaxeMethodImpl(ASTNode node) {
    super(node);
  }

}
