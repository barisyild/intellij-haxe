/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2020 AS3Boyan
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
package com.intellij.plugins.haxe.lang.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Key;
import com.intellij.plugins.haxe.lang.psi.HaxeAnonymousTypeBody;
import com.intellij.plugins.haxe.lang.psi.HaxeGenericParam;
import com.intellij.plugins.haxe.lang.psi.HaxeType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * This class is a helper class to handle type parameters with constrains from multiple types
 * https://haxe.org/manual/type-system-type-parameter-constraints.html
 *
 * It makes sure auto-completion and  method/field  resolves works.
 *  NOTE: It does NOT check it the types can actually be unified and is not intended to be used
 *  for anything else other than generics with multiple constraints
 */
public class HaxeTypeParameterMultiType extends AnonymousHaxeTypeImpl {

  private static final Key<HaxeTypeParameterMultiType>  ParameterMultiTypeKey = Key.create("parameterMultiType");
  private final List<HaxeType> typeList;
  private final List<HaxeAnonymousTypeBody> anonymousTypeBodyList;

  public static HaxeTypeParameterMultiType withTypeList(@NotNull ASTNode node, @NotNull List<HaxeType> typeList) {
    return  withCached(node, typeList, null);
  }
  public static HaxeTypeParameterMultiType withAnonymousList(@NotNull ASTNode node, @NotNull List<HaxeAnonymousTypeBody> anonymousTypeBodyList) {
    return   withCached(node, null, anonymousTypeBodyList);
  }
  public static HaxeTypeParameterMultiType withTypeAndAnonymousList(@NotNull ASTNode node,  @NotNull List<HaxeType> typeList,  @NotNull List<HaxeAnonymousTypeBody> anonymousTypeBodyList) {
    return withCached(node, typeList, anonymousTypeBodyList);
  }
  private static HaxeTypeParameterMultiType withCached(@NotNull ASTNode node, @Nullable List<HaxeType> typeList, @Nullable List<HaxeAnonymousTypeBody> anonymousTypeBodyList) {
    HaxeTypeParameterMultiType data = node.getUserData(ParameterMultiTypeKey);
    if (data != null) {
      return data;
    }
    data = new HaxeTypeParameterMultiType(node, typeList, anonymousTypeBodyList);
    node.putUserData(ParameterMultiTypeKey, data);
    return data;
  }

  private HaxeTypeParameterMultiType(@NotNull ASTNode node, List<HaxeType> typeList, List<HaxeAnonymousTypeBody> anonymousTypeBodyList) {
    super(node);
    this.typeList = typeList != null ? typeList : List.of();
    this.anonymousTypeBodyList = anonymousTypeBodyList  != null  ? anonymousTypeBodyList : List.of();
  }

  public List<HaxeType> getHaxeExtendsList() {
    return typeList;
  }


  @Override
  public @NotNull List<HaxeAnonymousTypeBody> getAnonymousTypeBodyList() {
    return anonymousTypeBodyList;
  }

  @Override
  public @NotNull List<HaxeType> getTypeList() {
    return typeList;
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    HaxeTypeParameterMultiType type = (HaxeTypeParameterMultiType)o;
    return Objects.equals(typeList, type.typeList) && Objects.equals(anonymousTypeBodyList, type.anonymousTypeBodyList);
  }

  @Override
  public int hashCode() {
    return Objects.hash(typeList, anonymousTypeBodyList);
  }
}
