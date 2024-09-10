/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2018 Ilya Malanin
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
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.HaxeAnonymousTypeModel;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import com.intellij.plugins.haxe.model.type.SpecificHaxeClassReference;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.impl.PsiClassImplUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author: Fedor.Korotkov
 */
public abstract class AnonymousHaxeTypeImpl extends AbstractHaxePsiClass implements HaxeAnonymousType {
  public AnonymousHaxeTypeImpl(@NotNull ASTNode node) {
    super(node);
  }

  @NotNull
  @Override
  public List<HaxeType> getHaxeExtendsList() {
    HaxeAnonymousTypeModel model = (HaxeAnonymousTypeModel) getModel();
    return model.getExtensionTypesPsi();
  }

  @Override
  @NotNull
  public PsiClass[] getSupers() {
    HaxeAnonymousTypeModel model = (HaxeAnonymousTypeModel) getModel();
    return model.getExtendsTypes().stream()
      .map(ResultHolder::getClassType)
      .filter(Objects::nonNull)
      .map(SpecificHaxeClassReference::getHaxeClass)
      .filter(Objects::nonNull)
      .toArray(PsiClass[]::new);
  }

  @Override
  public HaxeComponentName getComponentName() {
    return null;
  }

  @Override
  public HaxeGenericParam getGenericParam() {
    // anonymous types can have generics, but they are defined in parent
    // ex. typedef Iterator<T> = {function next():T;}
    if (getParent() instanceof HaxeTypeOrAnonymous typeOrAnonymous) {
      if( typeOrAnonymous.getParent() instanceof  HaxeTypedefDeclaration typedef) {
        return typedef.getGenericParam();
      }
    }
    return null;
  }

  @Nullable
  @Override
  public PsiIdentifier getNameIdentifier() {
    return new HaxeIdentifierImpl(new HaxeDummyASTNode("AnonymousType", AnonymousHaxeTypeImpl.this.getProject())) {
      @NotNull
      @Override
      public Project getProject() {
        return ((HaxeDummyASTNode)getNode()).getProject();
      }
    };
  }

  @Override
  public boolean isAnonymousType() {
    return true;
  }

}
