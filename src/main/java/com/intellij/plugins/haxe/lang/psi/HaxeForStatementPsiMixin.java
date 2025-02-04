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
package com.intellij.plugins.haxe.lang.psi;

import com.intellij.psi.PsiNameIdentifierOwner;

/**
 * We need a mixin specifically for the 'for' statement in order for it
 * to be both a statement and an AbstractHaxeNamedComponent.  The
 * former for Java functionality interfacing, and the latter so that
 * variable name lookup -- for variables declared within the statement
 * -- doesn't fail.
 * See HaxePsiCompositeElementImpl.getDeclarationToProcess() to see how
 * that works.
 */
//public interface HaxeForStatementPsiMixin extends HaxeStatementPsiMixin, PsiNameIdentifierOwner {
public interface HaxeForStatementPsiMixin extends HaxeStatementPsiMixin{

  // The funny thing is that we don't need any interfaces at this level.
  // We just need to introduce a specific inheritance.
}
