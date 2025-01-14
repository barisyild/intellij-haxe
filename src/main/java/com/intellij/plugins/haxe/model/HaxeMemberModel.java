/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2017-2018 Ilya Malanin
 * Copyright 2018-2020 Eric Bishton
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
package com.intellij.plugins.haxe.model;

import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.metadata.HaxeMetadataList;
import com.intellij.plugins.haxe.metadata.psi.HaxeMeta;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataCompileTimeMeta;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataContent;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMember;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.plugins.haxe.lang.psi.HaxePsiModifier.*;
import static com.intellij.plugins.haxe.metadata.psi.HaxeMeta.OP;
import static com.intellij.plugins.haxe.metadata.psi.HaxeMeta.OPTIONAL;

abstract public class HaxeMemberModel extends HaxeBaseMemberModel {

  public HaxeMemberModel(PsiMember basePsi) {
    super(basePsi);
  }

  public static HaxeMemberModel fromPsi(PsiElement psiElement) {
    return ObjectUtils.tryCast(HaxeBaseMemberModel.fromPsi(psiElement), HaxeMemberModel.class);
  }

  public PsiMember getMemberPsi() {
    return (PsiMember)basePsi;
  }

  public boolean isPublic() {
    HaxeClassModel declaringClass = getDeclaringClass();

    return hasModifier(PUBLIC)
           // Fields and methods of externs and interfaces are public by default, private modifier for them should be defined explicitly
           || ((declaringClass.isInterface() || declaringClass.isExtern()) && !hasModifier(PRIVATE))
           || isOverriddenPublicMethod()
           || getDeclaringClass().hasCompileTimeMeta(HaxeMeta.PUBLIC_FIELDS);
  }

  public boolean isFinal() {
    return hasModifier(FINAL);
  }
  public boolean isOverload() {
    return hasModifier(OVERLOAD);
  }
  public boolean hasOperatorMeta() {
    return getNamedComponentPsi().hasCompileTimeMetadata(OP);
  }
  public boolean hasOptionalMeta() {
    return getNamedComponentPsi().hasCompileTimeMetadata(OPTIONAL);
  }
  public boolean isOperator(String operator) {
    HaxeMetadataList list = getNamedComponentPsi().getMetadataList(HaxeMetadataCompileTimeMeta.class);
    return list.getCompileTimeMeta().stream()
      .filter(meta -> meta.isType(OP))
      .anyMatch(meta ->  hasOperatorMeta(meta.getContent(), operator));

  }

  private boolean hasOperatorMeta(HaxeMetadataContent content, String operator) {
    HaxeBinaryExpression binaryExpression = PsiTreeUtil.findChildOfType(content, HaxeBinaryExpression.class);
    if (binaryExpression == null) return false;

    @NotNull PsiElement[] children = binaryExpression.getChildren();
    if (children.length < 2) return false;
    return children[1].textMatches(operator);
  }

  private boolean isOverriddenPublicMethod() {
    if (hasModifier(OVERRIDE)) {
      final HaxeBaseMemberModel parentMember = getParentMember();
      if (parentMember instanceof HaxeMemberModel model) {
        return model.isPublic();
      }
    }

    return false;
  }

  @Override
  @Nullable
  public HaxeClassModel getDeclaringClass() {
    return  CachedValuesManager.getProjectPsiDependentCache(getMemberPsi(), HaxeMemberModel::_getDeclaringClass);
  }

  ///TODO make  model reusable and cache result
  public HaxeModuleModel getDeclaringModule() {
    HaxeModule module = PsiTreeUtil.getParentOfType(getMemberPsi(), HaxeModule.class);
    if (module == null) return null;
    return new HaxeModuleModel(module);
  }

  private static HaxeClassModel _getDeclaringClass(PsiMember member) {
    PsiClass containingClass = member.getContainingClass();
    if (containingClass instanceof HaxeClass haxeClass) {
      return haxeClass.getModel();
    }else {
      return null;
    }
  }

  public boolean isInInterface() {
    return getDeclaringClass().isInterface();
  }

  public boolean hasModifier(@HaxePsiModifier.ModifierConstant String modifier) {
    return this.getModifiers().hasModifier(modifier);
  }

  public boolean isStatic() {
    return hasModifier(HaxePsiModifier.STATIC);
  }

  public boolean isInline() {
    return hasModifier(INLINE);
  }


  private HaxeModifiersModel _modifiers;

  @NotNull
  public HaxeModifiersModel getModifiers() {
    if (_modifiers == null) _modifiers = new HaxeModifiersModel(basePsi);
    return _modifiers;
  }

  public HaxeBaseMemberModel getParentMember() {
    final HaxeClassModel aClass = getDeclaringClass().getParentClass();
    return (aClass != null) ? aClass.getMember(this.getName(), null) : null;
  }

  @Nullable
  @Override
  public FullyQualifiedInfo getQualifiedInfo() {
    if (getDeclaringClass() != null && isStatic() && isPublic()) {
      FullyQualifiedInfo containerInfo = getDeclaringClass().getQualifiedInfo();
      if (containerInfo != null) {
        return new FullyQualifiedInfo(containerInfo.packagePath, containerInfo.fileName, containerInfo.className, getName());
      }
    }
    return null;
  }
}
