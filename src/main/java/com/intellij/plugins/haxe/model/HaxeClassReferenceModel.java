/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2019 Eric Bishton
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
import com.intellij.plugins.haxe.model.type.HaxeClassReference;
import com.intellij.plugins.haxe.model.type.HaxeTypeResolver;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import com.intellij.plugins.haxe.model.type.SpecificHaxeClassReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class HaxeClassReferenceModel {
  public HaxeType type;
  private HaxeClass _clazz;

  public HaxeClassReferenceModel(HaxeType type) {
    this.type = type;
  }

  public HaxeType getPsi() {
    return type;
  }

  @NotNull
  public List<HaxeTypeParameterModel> getTypeParameters() {
    if (null == type) return HaxeTypeParameterModel.EMPTY_LIST;
    return HaxeTypeParameterModel.fromParameterSet(type.getTypeParam());
  }

  public boolean hasParameters() {
    return null != type && null != type.getTypeParam();
  }

  @Nullable
  public HaxeClassModel getHaxeClassModel() {
    if (_clazz == null) {
      SpecificHaxeClassReference classType = HaxeTypeResolver.getTypeFromType(type).getClassType();
      _clazz = classType != null ? classType.getHaxeClass() : null;
      //_clazz = HaxeResolveUtil.getHaxeClassResolveResult(type).getHaxeClass();
    }
    return (_clazz != null) ? _clazz.getModel() : null;
  }
  public SpecificHaxeClassReference getSpecificHaxeClassReference() {
    HaxeClassModel aClass = getHaxeClassModel();
    if (aClass != null){
      HaxeClassReference reference = aClass.getReference();
      List<HaxeTypeParameterModel> parameters = getTypeParameters();
      List<ResultHolder> generics = parameters.stream().map(model -> model.getTypeReference().getSpecificHaxeClassReference().createHolder()).toList();
      return SpecificHaxeClassReference.withGenerics(reference, generics.toArray(new ResultHolder[0]));
    }else {
      return SpecificHaxeClassReference.getUnknown(getPsi());
    }
  }

}
